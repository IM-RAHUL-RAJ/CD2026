#!/usr/bin/env python
"""Check data in DuckDB - Interactive query tool."""
import duckdb

DB_PATH = r"C:\Users\Administrator\AppData\Local\Temp\1\analytics.duckdb"

def print_menu():
    print("\n" + "="*70)
    print("DUCKDB DATA EXPLORER")
    print("="*70)
    print("1. Table Summary (row counts)")
    print("2. View dim_date sample")
    print("3. View dim_instrument (all)")
    print("4. View dim_account (all)")
    print("5. View fact_trades (all)")
    print("6. Trade statistics by instrument")
    print("7. Fill rate analysis")
    print("8. Account position/exposure")
    print("9. Search orders by symbol")
    print("0. Exit")
    print("="*70)

def show_table_summary(db):
    print("\n📊 TABLE SUMMARY")
    print("-" * 70)
    tables = ["dim_date", "dim_instrument", "dim_account", "fact_trades", "etl_watermark"]
    for table in tables:
        count = db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        print(f"  {table:25} {count:6} rows")

def show_dim_date(db):
    print("\n📅 DIM_DATE (Sample - First 10 rows)")
    print("-" * 70)
    result = db.execute("""
        SELECT date_key, full_date, day_name, month_name, is_weekday
        FROM dim_date
        LIMIT 10
    """).fetchall()
    for row in result:
        print(f"  {row[0]} | {row[1]} | {row[2]:9} | {row[3]:9} | WD:{row[4]}")

def show_dim_instrument(db):
    print("\n🏢 DIM_INSTRUMENT (All)")
    print("-" * 70)
    result = db.execute("""
        SELECT instrument_key, symbol, name, asset_class, currency
        FROM dim_instrument
        ORDER BY instrument_key
    """).fetchall()
    for key, sym, name, asset_class, curr in result:
        print(f"  [{key:2}] {sym:10} {name:15} {asset_class:8} {curr}")

def show_dim_account(db):
    print("\n👤 DIM_ACCOUNT (All - Type 2 SCD History)")
    print("-" * 70)
    result = db.execute("""
        SELECT account_key, account_id, holder_name, status, 
               effective_date, end_date, is_current
        FROM dim_account
        ORDER BY account_key
    """).fetchall()
    for row in result:
        end_date = row[5] if row[5] else "CURRENT"
        current = "✓" if row[6] else " "
        print(f"  [{row[0]}] Account {row[1]} | {row[2]:20} | {row[3]:10} | {current}")
        print(f"       From: {row[4]} → {end_date}")

def show_fact_trades(db):
    print("\n💱 FACT_TRADES (All)")
    print("-" * 70)
    result = db.execute("""
        SELECT 
            ft.source_order_id,
            di.symbol,
            ft.side,
            ft.quantity,
            ft.price,
            ft.status,
            ft.created_at
        FROM fact_trades ft
        JOIN dim_instrument di ON ft.instrument_key = di.instrument_key
        ORDER BY ft.source_order_id
    """).fetchall()
    print(f"  {'ID':3} {'Symbol':8} {'Side':4} {'Qty':5} {'Price':8} {'Status':10} {'Created':19}")
    print("  " + "-" * 66)
    for row in result:
        created_at = str(row[6])[:19]  # Truncate timestamp
        print(f"  {row[0]:3} {row[1]:8} {row[2]:4} {row[3]:5} ${row[4]:7.2f} {row[5]:10} {created_at}")

def show_statistics(db):
    print("\n📈 TRADE STATISTICS BY INSTRUMENT")
    print("-" * 70)
    result = db.execute("""
        SELECT 
            di.symbol,
            COUNT(*) as total_trades,
            SUM(CASE WHEN ft.status='FILLED' THEN 1 ELSE 0 END) as filled_trades,
            SUM(ft.quantity) as total_quantity,
            ROUND(AVG(ft.price), 2) as avg_price,
            MIN(ft.price) as min_price,
            MAX(ft.price) as max_price
        FROM fact_trades ft
        JOIN dim_instrument di ON ft.instrument_key = di.instrument_key
        GROUP BY di.symbol
        ORDER BY total_trades DESC
    """).fetchall()
    
    print(f"  {'Symbol':8} {'Total':6} {'Filled':7} {'Qty':6} {'Avg Price':10} {'Min':8} {'Max':8}")
    print("  " + "-" * 62)
    for sym, total, filled, qty, avg_price, min_price, max_price in result:
        print(f"  {sym:8} {total:6} {filled:7} {qty:6} ${avg_price:9.2f} ${min_price:7.2f} ${max_price:7.2f}")

def show_fill_rate(db):
    print("\n📊 FILL RATE ANALYSIS")
    print("-" * 70)
    result = db.execute("""
        SELECT 
            di.symbol,
            COUNT(*) as total_orders,
            SUM(CASE WHEN ft.status='FILLED' THEN 1 ELSE 0 END) as filled,
            SUM(CASE WHEN ft.status='REJECTED' THEN 1 ELSE 0 END) as rejected,
            SUM(CASE WHEN ft.status='CANCELLED' THEN 1 ELSE 0 END) as cancelled,
            ROUND(100.0 * SUM(CASE WHEN ft.status='FILLED' THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 1) as fill_rate
        FROM fact_trades ft
        JOIN dim_instrument di ON ft.instrument_key = di.instrument_key
        GROUP BY di.symbol
        ORDER BY fill_rate DESC
    """).fetchall()
    
    print(f"  {'Symbol':8} {'Total':7} {'Filled':7} {'Rejected':9} {'Cancelled':9} {'Fill %':8}")
    print("  " + "-" * 62)
    for sym, total, filled, rejected, cancelled, fill_rate in result:
        print(f"  {sym:8} {total:7} {filled:7} {rejected:9} {cancelled:9} {fill_rate:7.1f}%")

def show_position(db):
    print("\n💼 ACCOUNT POSITION/EXPOSURE")
    print("-" * 70)
    result = db.execute("""
        SELECT 
            di.symbol,
            SUM(CASE WHEN ft.side='BUY' THEN ft.quantity ELSE -ft.quantity END) as net_position,
            SUM(CASE WHEN ft.side='BUY' THEN ft.quantity ELSE 0 END) as bought,
            SUM(CASE WHEN ft.side='SELL' THEN ft.quantity ELSE 0 END) as sold
        FROM fact_trades ft
        JOIN dim_instrument di ON ft.instrument_key = di.instrument_key
        WHERE ft.status='FILLED'
        GROUP BY di.symbol
        HAVING net_position != 0
        ORDER BY ABS(net_position) DESC
    """).fetchall()
    
    if result:
        print(f"  {'Symbol':8} {'Position':10} {'Bought':8} {'Sold':8}")
        print("  " + "-" * 48)
        for sym, position, bought, sold in result:
            side = "LONG " if position > 0 else "SHORT"
            print(f"  {sym:8} {side}{abs(position):5} {bought:8} {sold:8}")
    else:
        print("  No open positions")

def search_by_symbol(db):
    symbol = input("\nEnter symbol to search: ").upper().strip()
    print(f"\n🔍 ORDERS FOR {symbol}")
    print("-" * 70)
    result = db.execute("""
        SELECT 
            ft.source_order_id,
            ft.side,
            ft.quantity,
            ft.price,
            ft.status,
            ft.executed_price,
            ft.created_at
        FROM fact_trades ft
        JOIN dim_instrument di ON ft.instrument_key = di.instrument_key
        WHERE di.symbol = ?
        ORDER BY ft.source_order_id
    """, [symbol]).fetchall()
    
    if result:
        print(f"  {'ID':3} {'Side':4} {'Qty':5} {'Price':8} {'Status':10} {'Exec Price':10} {'Created':19}")
        print("  " + "-" * 70)
        for row in result:
            created_at = str(row[6])[:19]
            print(f"  {row[0]:3} {row[1]:4} {row[2]:5} ${row[3]:7.2f} {row[4]:10} ${row[5]:9.2f} {created_at}")
    else:
        print(f"  No orders found for {symbol}")

def main():
    try:
        db = duckdb.connect(DB_PATH, read_only=True)
        print("\n✓ Connected to DuckDB")
        
        while True:
            print_menu()
            choice = input("Select option: ").strip()
            
            if choice == "1":
                show_table_summary(db)
            elif choice == "2":
                show_dim_date(db)
            elif choice == "3":
                show_dim_instrument(db)
            elif choice == "4":
                show_dim_account(db)
            elif choice == "5":
                show_fact_trades(db)
            elif choice == "6":
                show_statistics(db)
            elif choice == "7":
                show_fill_rate(db)
            elif choice == "8":
                show_position(db)
            elif choice == "9":
                search_by_symbol(db)
            elif choice == "0":
                print("\n👋 Goodbye!")
                break
            else:
                print("❌ Invalid option")
        
        db.close()
    except Exception as e:
        print(f"❌ Error: {e}")

if __name__ == "__main__":
    main()
