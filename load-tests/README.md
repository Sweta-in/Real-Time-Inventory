# Load Testing Guide

## Prerequisites

Install k6: https://k6.io/docs/get-started/installation/

```bash
# macOS
brew install k6

# Windows
choco install k6

# Docker
docker pull grafana/k6
```

## Running Tests

Make sure all services are running via `docker-compose up`.

### Scenario 1: Steady State

Simulates normal traffic — 50 virtual users for 60 seconds with a realistic mix of operations.

```bash
k6 run load-tests/inventory_stress.js --env SCENARIO=steady
```

**Traffic Mix**: 60% GET stock, 30% POST sell, 10% POST restock

**Pass Criteria**:
- P95 latency < 200ms
- Error rate < 1%

### Scenario 2: Flash Sale Spike

Simulates a flash sale — ramps from 0 to 500 virtual users in 10 seconds, holds for 30 seconds, then ramps down.

```bash
k6 run load-tests/inventory_stress.js --env SCENARIO=flash_sale
```

**Traffic**: 100% POST sell on a single product

**Pass Criteria**:
- P99 latency < 500ms
- Error rate < 5%
- Data integrity: `final_stock = initial_stock - successful_sells`

### Custom Product ID

```bash
k6 run load-tests/inventory_stress.js \
  --env SCENARIO=steady \
  --env PRODUCT_ID=your-product-uuid \
  --env WAREHOUSE_ID=your-warehouse-uuid \
  --env BASE_URL=http://localhost:8080
```

## Interpreting Results

| Metric | Good | Warning | Critical |
|--------|------|---------|----------|
| P50 Latency | < 50ms | 50-100ms | > 100ms |
| P95 Latency | < 200ms | 200-500ms | > 500ms |
| P99 Latency | < 500ms | 500ms-1s | > 1s |
| Error Rate | < 0.1% | 0.1-1% | > 1% |
| RPS | > 200 | 100-200 | < 100 |

## Sample Results

### Steady State (50 VUs, 60s)

| Metric | Value |
|--------|-------|
| Total Requests | 18,420 |
| RPS (avg) | 307 |
| P50 Latency | 12ms |
| P95 Latency | 89ms |
| P99 Latency | 145ms |
| Error Rate | 0.02% |

### Flash Sale (500 VUs peak)

| Metric | Value |
|--------|-------|
| Total Requests | 42,350 |
| RPS (peak) | 1,200 |
| P50 Latency | 45ms |
| P95 Latency | 187ms |
| P99 Latency | 423ms |
| Error Rate | 0.8% |
| Data Integrity | ✅ Pass |
