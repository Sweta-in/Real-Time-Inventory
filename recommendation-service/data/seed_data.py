"""
Synthetic data generator for ML model training.
Generates 1000 users, 200 products, and 50k purchase interactions.
"""
import os
import random
import uuid
from datetime import datetime, timedelta

import psycopg2
import pandas as pd
import numpy as np


CATEGORIES = [
    "Electronics", "Clothing", "Home & Kitchen", "Books", "Sports",
    "Toys", "Beauty", "Automotive", "Garden", "Food"
]

ADJECTIVES = [
    "Premium", "Ultra", "Pro", "Essential", "Classic", "Modern",
    "Deluxe", "Smart", "Eco", "Advanced"
]

NOUNS = {
    "Electronics": ["Headphones", "Charger", "Speaker", "Keyboard", "Mouse", "Monitor", "Tablet", "Camera"],
    "Clothing": ["T-Shirt", "Jacket", "Sneakers", "Jeans", "Hoodie", "Cap", "Socks", "Belt"],
    "Home & Kitchen": ["Blender", "Toaster", "Lamp", "Pillow", "Mug", "Pan", "Knife Set", "Towel"],
    "Books": ["Novel", "Cookbook", "Textbook", "Journal", "Guide", "Manual", "Biography", "Anthology"],
    "Sports": ["Yoga Mat", "Dumbbell", "Jersey", "Water Bottle", "Racket", "Ball", "Gloves", "Helmet"],
    "Toys": ["Action Figure", "Puzzle", "Board Game", "Doll", "Building Set", "RC Car", "Plush Toy", "Drone"],
    "Beauty": ["Moisturizer", "Shampoo", "Lipstick", "Serum", "Sunscreen", "Perfume", "Brush Set", "Mask"],
    "Automotive": ["Floor Mat", "Air Freshener", "Phone Mount", "Dash Cam", "Seat Cover", "Wax", "Tool Kit", "Light"],
    "Garden": ["Plant Pot", "Hose", "Shovel", "Seeds", "Fertilizer", "Gloves", "Sprinkler", "Mulch"],
    "Food": ["Protein Bar", "Coffee Beans", "Snack Mix", "Sauce", "Spice Set", "Tea", "Granola", "Honey"],
}


def generate_products(n=200):
    products = []
    for i in range(n):
        cat = random.choice(CATEGORIES)
        adj = random.choice(ADJECTIVES)
        noun = random.choice(NOUNS[cat])
        name = f"{adj} {noun} {i+1}"
        desc = f"High-quality {cat.lower()} product: {name}. Perfect for everyday use."
        price = round(random.uniform(5.0, 500.0), 2)
        products.append({
            "id": str(uuid.uuid4()),
            "name": name,
            "category": cat,
            "description": desc,
            "base_price": price,
            "image_url": f"https://picsum.photos/seed/{i}/200/200",
            "created_at": datetime.now() - timedelta(days=random.randint(30, 365))
        })
    return products


def generate_users(n=1000):
    users = []
    for i in range(n):
        users.append({
            "id": str(uuid.uuid4()),
            "username": f"user_{i+1}",
            "email": f"user_{i+1}@example.com",
            "created_at": datetime.now() - timedelta(days=random.randint(30, 365))
        })
    return users


def generate_interactions(users, products, n=50000):
    interactions = []
    product_popularity = np.random.power(0.3, len(products))
    product_popularity /= product_popularity.sum()

    for _ in range(n):
        user = random.choice(users)
        product_idx = np.random.choice(len(products), p=product_popularity)
        product = products[product_idx]
        qty = random.randint(1, 5)
        price = round(product["base_price"] * qty * random.uniform(0.8, 1.2), 2)
        interactions.append({
            "id": str(uuid.uuid4()),
            "user_id": user["id"],
            "product_id": product["id"],
            "quantity": qty,
            "price_paid": price,
            "timestamp": datetime.now() - timedelta(
                days=random.randint(0, 180),
                hours=random.randint(0, 23),
                minutes=random.randint(0, 59)
            )
        })
    return interactions


def seed_to_database():
    db_url = os.getenv("DATABASE_URL", "postgresql://app_user:changeme_postgres_2024@localhost:5432/inventory_db")

    print("Generating synthetic data...")
    products = generate_products(200)
    users = generate_users(1000)
    interactions = generate_interactions(users, products, 50000)

    print(f"Generated {len(products)} products, {len(users)} users, {len(interactions)} interactions")

    conn = psycopg2.connect(db_url)
    cur = conn.cursor()

    print("Inserting products...")
    for p in products:
        cur.execute(
            """INSERT INTO products (id, name, category, description, base_price, image_url, created_at)
               VALUES (%s, %s, %s, %s, %s, %s, %s) ON CONFLICT DO NOTHING""",
            (p["id"], p["name"], p["category"], p["description"],
             p["base_price"], p["image_url"], p["created_at"])
        )

    print("Inserting users...")
    for u in users:
        cur.execute(
            """INSERT INTO users (id, username, email, created_at)
               VALUES (%s, %s, %s, %s) ON CONFLICT DO NOTHING""",
            (u["id"], u["username"], u["email"], u["created_at"])
        )

    print("Inserting purchase events...")
    for i in interactions:
        cur.execute(
            """INSERT INTO purchase_events (id, user_id, product_id, quantity, price_paid, timestamp)
               VALUES (%s, %s, %s, %s, %s, %s) ON CONFLICT DO NOTHING""",
            (i["id"], i["user_id"], i["product_id"],
             i["quantity"], i["price_paid"], i["timestamp"])
        )

    # Create warehouse and inventory items
    warehouses = [
        {"id": str(uuid.uuid4()), "name": "Warehouse A", "location": "New York", "is_active": True},
        {"id": str(uuid.uuid4()), "name": "Warehouse B", "location": "Los Angeles", "is_active": True},
    ]

    print("Inserting warehouses...")
    for w in warehouses:
        cur.execute(
            """INSERT INTO warehouses (id, name, location, is_active)
               VALUES (%s, %s, %s, %s) ON CONFLICT DO NOTHING""",
            (w["id"], w["name"], w["location"], w["is_active"])
        )

    print("Creating inventory items...")
    for p in products:
        for w in warehouses:
            stock = random.randint(10, 500)
            capacity = random.randint(stock, 1000)
            cur.execute(
                """INSERT INTO inventory_items (id, product_id, warehouse_id, stock_level, capacity_max, last_updated)
                   VALUES (%s, %s, %s, %s, %s, %s) ON CONFLICT DO NOTHING""",
                (str(uuid.uuid4()), p["id"], w["id"], stock, capacity, datetime.now())
            )

    conn.commit()
    cur.close()
    conn.close()

    # Also save as CSV for offline model training
    os.makedirs("/app/data", exist_ok=True)
    pd.DataFrame(interactions).to_csv("/app/data/interactions.csv", index=False)
    pd.DataFrame(products).to_csv("/app/data/products.csv", index=False)
    pd.DataFrame(users).to_csv("/app/data/users.csv", index=False)

    print("✅ Seed data complete!")


if __name__ == "__main__":
    seed_to_database()
