"""
ReAct Agent — Reasoning + Acting loop powered by Groq LLM.
Implements manual tool calling with TOOL_CALL/FINAL_DECISION parsing.
"""
import os, json, time, re, logging, uuid
from datetime import datetime
from typing import Optional
from groq import Groq
from tenacity import retry, stop_after_attempt, wait_exponential, retry_if_exception_type
from prometheus_client import Counter, Histogram
from tools import TOOLS, execute_tool

logger = logging.getLogger("reorder-agent-service")

GROQ_API_CALLS = Counter("groq_api_calls_total", "Total Groq API calls")
GROQ_TOKENS_USED = Counter("groq_tokens_used_total", "Total tokens consumed", ["type"])
AGENT_REASONING_LATENCY = Histogram("agent_reasoning_latency_seconds", "Agent reasoning latency", buckets=[1,2,5,10,20,30,60,120])
REORDER_DECISIONS = Counter("reorder_decisions_total", "Reorder decisions", ["action"])

GROQ_API_KEY = os.getenv("GROQ_API_KEY", "")
GROQ_MODEL = os.getenv("GROQ_MODEL", "llama-3.3-70b-versatile")
MAX_REACT_STEPS = int(os.getenv("MAX_REACT_STEPS", "12"))
MAX_CONTEXT_TOKENS = int(os.getenv("MAX_CONTEXT_TOKENS", "7000"))

TOOLS_DESC = "\n".join(f"- **{t['name']}**: {t['description']}\n  Params: {json.dumps(t['parameters'])}" for t in TOOLS)

SYSTEM_PROMPT = f"""You are an autonomous inventory reorder agent. Analyze demand forecasts, check stock, and decide reorders.

## Tools
{TOOLS_DESC}

## Format
When calling a tool, output on its own line:
TOOL_CALL: {{"tool": "name", "args": {{...}}}}

When done, output:
FINAL_DECISION: {{"action": "reorder" or "no_action", "summary": "...", "reorders": [...]}}

## Rules
- Reorder if predicted_demand_7d > current_stock
- Supplier lead time: {os.getenv("SUPPLIER_LEAD_TIME_DAYS", "3")} days
- Don't reorder slow movers. Budget cap: ${os.getenv("MAX_REORDER_BUDGET_USD", "5000")}
- Include 1.5x daily demand safety stock. Skip if stock_ratio > 0.5
- Check inventory BEFORE reordering. Calculate quantity BEFORE executing.
"""


class ReActAgent:
    def __init__(self):
        self.client = Groq(api_key=GROQ_API_KEY) if GROQ_API_KEY else None

    @retry(retry=retry_if_exception_type(Exception), stop=stop_after_attempt(3),
           wait=wait_exponential(multiplier=2, min=2, max=30))
    def _call_groq(self, messages):
        if not self.client:
            raise RuntimeError("GROQ_API_KEY not set")
        start = time.time()
        GROQ_API_CALLS.inc()
        resp = self.client.chat.completions.create(model=GROQ_MODEL, messages=messages, temperature=0.3, max_tokens=1500)
        latency = time.time() - start
        u = resp.usage
        if u:
            GROQ_TOKENS_USED.labels(type="prompt").inc(u.prompt_tokens)
            GROQ_TOKENS_USED.labels(type="completion").inc(u.completion_tokens)
        return {"content": resp.choices[0].message.content or "", "prompt_tokens": u.prompt_tokens if u else 0,
                "completion_tokens": u.completion_tokens if u else 0, "latency_seconds": round(latency, 3)}

    def _compress(self, messages):
        if len(messages) <= 4:
            return messages
        sys_msg, recent, middle = messages[0], messages[-4:], messages[1:-4]
        if not middle:
            return messages
        summary = "SUMMARY:\n" + "\n".join(f"[{m['role']}]: {m['content'][:150]}..." for m in middle)
        return [sys_msg, {"role": "user", "content": summary}] + recent

    async def run(self, task: str, correlation_id: Optional[str] = None, product_id: Optional[str] = None) -> dict:
        if not self.client:
            return {"status": "error", "error": "GROQ_API_KEY not configured", "reasoning_trace": [], "final_decision": None}

        cid = correlation_id or str(uuid.uuid4())
        start_time = time.time()
        trace, total_calls, total_tokens = [], 0, 0
        messages = [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": task}]

        for step in range(MAX_REACT_STEPS):
            est = sum(len(m.get("content", "")) for m in messages) // 4
            if est > MAX_CONTEXT_TOKENS:
                messages = self._compress(messages)

            try:
                result = self._call_groq(messages)
            except Exception as e:
                trace.append({"step": step+1, "type": "error", "content": str(e), "timestamp": datetime.utcnow().isoformat()})
                break

            content = result["content"]
            total_calls += 1
            total_tokens += result["prompt_tokens"] + result["completion_tokens"]
            rec = {"step": step+1, "type": "reasoning", "content": content, "tokens": result["prompt_tokens"]+result["completion_tokens"],
                   "latency_seconds": result["latency_seconds"], "timestamp": datetime.utcnow().isoformat()}

            # Check FINAL_DECISION
            fm = re.search(r"FINAL_DECISION:\s*(\{.*\})", content, re.DOTALL)
            if fm:
                try:
                    fd = json.loads(fm.group(1))
                    rec["type"], rec["decision"] = "final_decision", fd
                    trace.append(rec)
                    REORDER_DECISIONS.labels(action=fd.get("action", "no_action")).inc()
                    elapsed = time.time() - start_time
                    AGENT_REASONING_LATENCY.observe(elapsed)
                    return {"status": "completed", "correlation_id": cid, "reasoning_trace": trace, "final_decision": fd,
                            "total_llm_calls": total_calls, "total_tokens_used": total_tokens, "total_latency_seconds": round(elapsed, 3)}
                except json.JSONDecodeError:
                    rec["parse_error"] = "Invalid FINAL_DECISION JSON"

            # Check TOOL_CALL
            tm = re.search(r"TOOL_CALL:\s*(\{.*?\})", content, re.DOTALL)
            if tm:
                try:
                    tc = json.loads(tm.group(1))
                    tname, targs = tc.get("tool", ""), tc.get("args", {})
                    rec["type"], rec["tool_name"], rec["tool_args"] = "tool_call", tname, targs
                    trace.append(rec)
                    tr = await execute_tool(tname, targs)
                    trace.append({"step": step+1, "type": "observation", "tool_name": tname, "result": tr, "timestamp": datetime.utcnow().isoformat()})
                    messages.append({"role": "assistant", "content": content})
                    messages.append({"role": "user", "content": f"Observation ({tname}):\n{json.dumps(tr, indent=2)}"})
                    continue
                except json.JSONDecodeError:
                    rec["parse_error"] = "Invalid TOOL_CALL JSON"

            trace.append(rec)
            messages.append({"role": "assistant", "content": content})
            messages.append({"role": "user", "content": "Continue. Use TOOL_CALL or FINAL_DECISION."})

        elapsed = time.time() - start_time
        AGENT_REASONING_LATENCY.observe(elapsed)
        REORDER_DECISIONS.labels(action="timeout").inc()
        return {"status": "timeout", "correlation_id": cid, "reasoning_trace": trace,
                "final_decision": {"action": "no_action", "summary": "Max steps reached"},
                "total_llm_calls": total_calls, "total_tokens_used": total_tokens, "total_latency_seconds": round(elapsed, 3)}
