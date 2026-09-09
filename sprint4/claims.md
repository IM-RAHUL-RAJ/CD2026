# Enterprise Trading Platform: Claims & Pipeline Specification

## Executive Summary
This project implements a financial market data **ETL & Exploratory Data Analysis (EDA)** pipeline for the Enterprise Trading Platform. It extracts stock market candle data via the Fauxnance REST API, cleans and normalizes market attributes, loads the data into a DuckDB dimensional analytical star schema (`analytics.duckdb`), and builds an interactive HTML visualization dashboard (`templates/report.html`).

---

## Programme Manifest & Package Architecture

### Package Information
* **Package Name**: `src`
* **Project Identifier**: `leap-capstone-eda`
* **Manifest File**: `manifest.env`

### Module & Function Specifications

| Stage | Module Name | Class / Function Name | Primary Responsibility |
|---|---|---|---|
| **Extraction** | `src.extract.extract` | `extract_data(symbols, start_date, end_date)`<br>`save_extracted_data(success_data, output_dir)` | Queries Fauxnance API with rate-limit handling & disk caching; outputs CSVs to `data/`. |
| **Transformation** | `src.transform.transform` | `transform()` | Cleans headers, parses dates, repairs OHLC boundaries, outputs to `transformed/`. |
| **Load (Database)** | `src.load.load` | `TradingLoader` | Manages DuckDB connection, creates star schema (`analytics-schema.sql`), populates `dim_instrument`, `dim_date`, `dim_account`, `fact_trades`. |
| **EDA Dashboard** | `src.eda.generate_dashboard` | `create_dashboard(symbols)`<br>`load_data(symbols)` | Queries DuckDB for active symbols, calculates returns, volatility, Sharpe ratio, consistency, correlation, and writes `templates/report.html`. |
| **Pipeline Core** | `src.pipeline.pipeline` | `run_pipeline(symbols, skip_extract)`<br>`start_etl(symbols)` | Orchestrates end-to-end execution: Extract -> Transform -> Load -> EDA. |
| **Web Server API** | `src.api.app` | `app` (Flask application) | Serves the web UI (`index.html`) and handles batch analysis requests (`POST /extract`). |

---

## Entry Point Commands

### 1. Full Pipeline Execution (CLI Entry Point)
To run the complete end-to-end pipeline (Extract -> Transform -> Load -> EDA):

```powershell
python -m src.pipeline.pipeline
```

Or run with custom stock ticker symbols:
```powershell
python -m src.pipeline.pipeline --symbols INFY.NS TATASTEEL.NS RELIANCE.NS MSFT GOOGL
```

To skip the extraction step and re-process existing local files in `data/`:
```powershell
python -m src.pipeline.pipeline --skip-extract
```

If installed as a package via `pip install -e .`:
```powershell
fauxnance-pipeline --symbols INFY.NS TATASTEEL.NS RELIANCE.NS MSFT GOOGL
```

---

### 2. Web Application Server Entry Point
To start the Flask web application and interactive stock analyzer interface:

```powershell
python -m src.api.app
```

If installed as a package:
```powershell
fauxnance-serve
```

* **Web UI Access**: Open **http://127.0.0.1:5000** in your browser.
* **REST Endpoints**:
  * `GET /` — Serves the interactive stock selection UI (`index.html`).
  * `GET /health` — Checks Fauxnance API connection status.
  * `GET /usage` — Queries remaining API quota.
  * `GET /candles/<symbol>` — Fetches raw candle JSON for a symbol.
  * `POST /extract` — Triggers 30-day extraction, transformation, DuckDB load, and returns rendered report dashboard (`templates/report.html`).

---

### 3. Automated Test Suite Entry Point Command
To execute all automated unit tests across API client, Flask routes, ETL pipeline, and data transformations:

```powershell
pytest -v
```

#### Test Manifest Specifications

| Test Module | Test Class / Function | Target Component Tested |
|---|---|---|
| `tests/test_api_client.py` | `TestFauxnanceClient`<br>`TestExtractCoordination`<br>`TestFlaskRoutes` | Fauxnance API client caching, rate limiting, connection retries, error codes, and Flask web routes. |
| `tests/test_transform.py` | `test_transformed_file` | CSV schema validation, non-negative price enforcement, OHLC ordering constraints, date monotonicity. |
| `tests/test_pipeline.py` | `test_run_pipeline_skip_extract` | End-to-end integration test verifying complete pipeline execution without errors. |

---

## Data Schema & Analytical Claims

### DuckDB Star Schema Architecture (`analytics.duckdb`)
* **`dim_instrument`**: Stores instrument surrogate keys, symbols, exchange mappings, asset classes, and currencies.
* **`dim_date`**: Calendar date dimension (`YYYYMMDD` keys, full dates, quarters, weekdays).
* **`dim_account`**: Trading account dimension (`account_key`, `account_id`, `holder_name`, `status`).
* **`fact_trades`**: Fact table recording trade executions (`trade_key`, `account_key`, `instrument_key`, `date_key`, `quantity`, `price`, `trade_value`, `side`, `status`, `source_order_id`).

### Key Analytical Metrics & Claims Computed
1. **Strongest Overall Growth**: Identifies stock delivering highest total percentage return.
2. **Risk vs Return (Sharpe Ratio)**: Computes risk-adjusted performance based on daily return mean divided by volatility.
3. **Movement Correlation**: Generates cross-instrument correlation matrix using daily return pct changes.
4. **Performance Consistency**: Ranks stocks by percentage of trading days with positive price movement.
