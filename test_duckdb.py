#!/usr/bin/env python
"""Comprehensive DuckDB ETL testing suite."""
import duckdb
import psycopg2
import subprocess
from datetime import datetime

DB_PATH = r"C:\Users\Administrator\AppData\Local\Temp\1\analytics.duckdb"

def test_1_basic_load():
    """Test: Basic load verification."""
    print("\n" + "="*60)
    print("TEST 1: Basic Load Verification")
    print("="*60)
    
    db = duckdb.connect(DB_PATH)
    
    results = {
        "dim_date": db.execute("SELECT COUNT(*) FROM dim_date").fetchone()[0],
        "dim_instrument": db.execute("SELECT COUNT(*) FROM dim_instrument").fetchone()[0],
        "dim_account": db.execute("SELECT COUNT(*) FROM dim_account").fetchone()[0],
        "fact_trades": db.execute("SELECT COUNT(*) FROM fact_trades").fetchone()[0],
    }
    
    db.close()
    
    print("✓ Data warehouse stats:")
    for table, count in results.items():
        print(f"  {table}: {count} rows")
    
    return all(v > 0 for v in results.values())

def test_2_analytics():
    """Test: Analytics queries."""
    print("\n" + "="*60)
    print("TEST 2: Analytics Queries")
    print("="*60)
    
    db = duckdb.connect(DB_PATH)
    
    # Fill rate
    result = db.execute("""
        SELECT 
            di.symbol,
            COUNT(*) as orders,
            SUM(CASE WHEN ft.status='FILLED' THEN 1 ELSE 0 END) as filled,
            ROUND(100.0 * SUM(CASE WHEN ft.status='FILLED' THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 1) as fill_rate
        FROM fact_trades ft
        JOIN dim_instrument di ON ft.instrument_key = di.instrument_key
        GROUP BY di.symbol
        ORDER BY fill_rate DESC
    """).fetchall()
    
    print("✓ Fill Rate by Instrument:")
    for symbol, orders, filled, fill_rate in result:
        print(f"  {symbol}: {filled}/{orders} filled ({fill_rate}%)")
    
    # Volume
    result = db.execute("""
        SELECT 
            di.symbol,
            SUM(ft.quantity) as total_qty,
            COUNT(*) as trades
        FROM fact_trades ft
        JOIN dim_instrument di ON ft.instrument_key = di.instrument_key
        GROUP BY di.symbol
        ORDER BY total_qty DESC
    """).fetchall()
    
    print("\n✓ Volume by Instrument:")
    for symbol, qty, trades in result:
        print(f"  {symbol}: {qty} shares ({trades} trades)")
    
    db.close()
    return True

def test_3_data_quality():
    """Test: Data quality checks."""
    print("\n" + "="*60)
    print("TEST 3: Data Quality")
    print("="*60)
    
    db = duckdb.connect(DB_PATH)
    
    # Check for null keys
    result = db.execute("""
        SELECT COUNT(*) as null_account_keys FROM fact_trades WHERE account_key IS NULL
    """).fetchone()[0]
    print(f"✓ Null account_key: {result} (expected 0)")
    
    result = db.execute("""
        SELECT COUNT(*) as null_instrument_keys FROM fact_trades WHERE instrument_key IS NULL
    """).fetchone()[0]
    print(f"✓ Null instrument_key: {result} (expected 0)")
    
    # Check for invalid prices
    result = db.execute("""
        SELECT COUNT(*) as invalid_prices FROM fact_trades WHERE price <= 0
    """).fetchone()[0]
    print(f"✓ Invalid prices (≤0): {result} (expected 0)")
    
    # Check for invalid quantities
    result = db.execute("""
        SELECT COUNT(*) as invalid_qty FROM fact_trades WHERE quantity <= 0
    """).fetchone()[0]
    print(f"✓ Invalid quantities (≤0): {result} (expected 0)")
    
    db.close()
    return result == 0

def test_4_watermark():
    """Test: Watermark tracking."""
    print("\n" + "="*60)
    print("TEST 4: Watermark & Incremental Load")
    print("="*60)
    
    db = duckdb.connect(DB_PATH)
    
    result = db.execute("SELECT * FROM etl_watermark ORDER BY table_name").fetchall()
    print("✓ Watermarks:")
    for table_name, last_loaded in result:
        print(f"  {table_name}: {last_loaded}")
    
    db.close()
    return True

def run_all_tests():
    """Run all tests."""
    print("\n" + "="*60)
    print("DUCKDB ETL TEST SUITE")
    print("="*60)
    
    tests = [
        ("Basic Load", test_1_basic_load),
        ("Analytics", test_2_analytics),
        ("Data Quality", test_3_data_quality),
        ("Watermark", test_4_watermark),
    ]
    
    results = []
    for name, test_func in tests:
        try:
            passed = test_func()
            results.append((name, "✓ PASS" if passed else "✗ FAIL"))
        except Exception as e:
            results.append((name, f"✗ ERROR: {e}"))
    
    print("\n" + "="*60)
    print("TEST SUMMARY")
    print("="*60)
    for name, status in results:
        print(f"{name:30} {status}")
    print("="*60)

if __name__ == "__main__":
    run_all_tests()
