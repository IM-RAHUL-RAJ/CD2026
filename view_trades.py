#!/usr/bin/env python
"""Display all trades with instrument symbols."""
import duckdb

db = duckdb.connect(r"C:\Users\Administrator\AppData\Local\Temp\1\analytics.duckdb")

result = db.execute("""
    SELECT source_order_id, di.symbol, ft.side, ft.quantity, ft.price, ft.status 
    FROM fact_trades ft 
    JOIN dim_instrument di ON ft.instrument_key=di.instrument_key
    ORDER BY source_order_id
""").fetchall()

print("\n" + "="*80)
print("ALL TRADES WITH INSTRUMENT SYMBOLS")
print("="*80)
print(f"{'ID':3} {'Symbol':8} {'Side':4} {'Qty':6} {'Price':10} {'Status':10}")
print("-" * 80)

for row in result:
    order_id, symbol, side, qty, price, status = row
    print(f"{order_id:3} {symbol:8} {side:4} {qty:6} ${price:9.2f} {status:10}")

print("-" * 80)
print(f"Total trades: {len(result)}")

db.close()
