import os
import duckdb
import pandas as pd
import numpy as np
import plotly.graph_objects as go
import plotly.express as px
from plotly.offline import plot


DATABASE_FILE = "analytics.duckdb"
OUTPUT_FILE = "templates/report.html"


def load_data(symbols=None):
    conn = duckdb.connect(DATABASE_FILE)
    try:
        query = """
            SELECT
                i.symbol,
                d.full_date AS date,
                f.quantity,
                f.price,
                f.quantity * f.price AS trade_value
            FROM fact_trades f
            JOIN dim_instrument i ON f.instrument_key = i.instrument_key
            JOIN dim_date d ON f.date_key = d.date_key
        """
        params = []
        if symbols:
            target_set = set()
            for s in symbols:
                if isinstance(s, str) and s.strip():
                    u = s.strip().upper()
                    target_set.add(u)
                    target_set.add(u.replace(".", "_"))
                    target_set.add(u.replace("_", "."))
            if target_set:
                placeholders = ", ".join(["?"] * len(target_set))
                query += f" WHERE UPPER(i.symbol) IN ({placeholders})"
                params.extend(list(target_set))

        query += " ORDER BY i.symbol, d.full_date"
        data = conn.execute(query, params).fetchdf()
    finally:
        conn.close()

    if data.empty:
        raise Exception("No matching trading data found in fact_trades")

    data.columns = data.columns.str.lower().str.strip()
    data["date"] = pd.to_datetime(data["date"], errors="coerce")
    data["trade_value"] = pd.to_numeric(data["trade_value"], errors="coerce")
    data["price"] = pd.to_numeric(data["price"], errors="coerce")
    data["quantity"] = pd.to_numeric(data["quantity"], errors="coerce")
    data = data.dropna(subset=["symbol", "date", "price"])
    data["close"] = data["price"]
    data = data.sort_values(["symbol", "date"])
    return data


def calculate_metrics(df):
    results = []
    for symbol, stock in df.groupby("symbol"):
        stock = stock.copy()
        stock["daily_return"] = stock["close"].pct_change()

        total_return = (stock["close"].iloc[-1] - stock["close"].iloc[0]) / stock["close"].iloc[0]
        volatility = stock["daily_return"].std()
        sharpe = stock["daily_return"].mean() / volatility if volatility != 0 else 0
        consistency = stock["daily_return"].gt(0).mean()

        results.append({
            "symbol": symbol,
            "return": round(total_return * 100, 2),
            "volatility": round(volatility * 100, 2),
            "sharpe": round(sharpe, 2),
            "consistency": round(consistency * 100, 2),
        })
    return pd.DataFrame(results)


def generate_claims(metrics, correlation):
    best_return = metrics.sort_values("return", ascending=False).iloc[0]
    best_sharpe = metrics.sort_values("sharpe", ascending=False).iloc[0]
    most_consistent = metrics.sort_values("consistency", ascending=False).iloc[0]

    corr = correlation.copy()
    arr = corr.to_numpy(copy=True)
    np.fill_diagonal(arr, 0)
    corr = pd.DataFrame(arr, index=correlation.index, columns=correlation.columns)
    upper = corr.where(np.triu(np.ones(corr.shape), k=1).astype(bool))
    pair_values = upper.stack().sort_values(ascending=False)

    if len(pair_values) > 0:
        pair = pair_values.index[0]
        corr_value = pair_values.iloc[0]
    else:
        pair = ("NA", "NA")
        corr_value = 0

    return [
        {
            "title": "Strongest overall growth",
            "finding": (
                f"{best_return.symbol} delivered the highest return at "
                f"{best_return['return']}% over the analysed period."
            ),
        },
        {
            "title": "Best balance of return and stability",
            "finding": (
                f"{best_sharpe.symbol} had the strongest risk-adjusted performance "
                f"with a Sharpe ratio of {best_sharpe.sharpe}."
            ),
        },
        {
            "title": "Closest movement pattern",
            "finding": (
                f"{pair[0]} and {pair[1]} had the strongest relationship "
                f"with a correlation of {round(corr_value, 2)}."
            ),
        },
        {
            "title": "Most consistent performance",
            "finding": (
                f"{most_consistent.symbol} recorded positive movement on "
                f"{most_consistent.consistency}% of trading days, "
                f"the highest among the selected stocks."
            ),
        },
    ]


def create_charts(df, metrics):
    charts = []
    layout_base = dict(
        template="plotly_white",
        paper_bgcolor="white",
        plot_bgcolor="white",
        font=dict(family="Arial, sans-serif", size=13),
        margin=dict(l=60, r=30, t=60, b=55),
    )

    # Cumulative return
    fig = go.Figure()
    for symbol, stock in df.groupby("symbol"):
        growth = (stock["close"] / stock["close"].iloc[0] - 1) * 100
        fig.add_trace(go.Scatter(x=stock["date"], y=growth, name=symbol, mode="lines", line=dict(width=2)))
    fig.update_layout(
        title="Cumulative Return",
        xaxis_title="Date",
        yaxis_title="Return (%)",
        hovermode="x unified",
        legend=dict(orientation="h", yanchor="bottom", y=1.02, xanchor="center", x=0.5),
        **layout_base,
    )
    fig.update_xaxes(showgrid=False)
    fig.update_yaxes(gridcolor="#e5e7eb", zeroline=True)
    charts.append(("Cumulative Return", "Shows how each stock performed relative to its starting price.", plot(fig, output_type="div", include_plotlyjs=False, config={"responsive": True})))

    # Risk vs return
    fig2 = px.scatter(metrics, x="volatility", y="return", text="symbol")
    fig2.update_traces(textposition="top center", marker=dict(size=14, line=dict(width=1)))
    fig2.update_layout(title="Risk vs Return", xaxis_title="Volatility (%)", yaxis_title="Return (%)", **layout_base)
    fig2.update_xaxes(gridcolor="#e5e7eb")
    fig2.update_yaxes(gridcolor="#e5e7eb", zeroline=True)
    charts.append(("Risk vs Return", "Compares the return achieved by each stock with the amount of price fluctuation.", plot(fig2, output_type="div", include_plotlyjs=False, config={"responsive": True})))

    # Correlation heatmap
    pivot = df.pivot_table(index="date", columns="symbol", values="close")
    corr = pivot.pct_change().corr()
    fig3 = px.imshow(corr, text_auto=".2f", aspect="auto")
    fig3.update_layout(title="Stock Movement Correlation", xaxis_title="Stock", yaxis_title="Stock", **layout_base)
    charts.append(("Stock Movement Correlation", "Higher values indicate stocks that tended to move more similarly.", plot(fig3, output_type="div", include_plotlyjs=False, config={"responsive": True})))

    # Consistency bar
    metrics_sorted = metrics.sort_values("consistency", ascending=False)
    fig4 = px.bar(metrics_sorted, x="symbol", y="consistency", text="consistency")
    fig4.update_traces(texttemplate="%{text:.2f}%", textposition="outside")
    fig4.update_layout(title="Positive Trading Days", xaxis_title="Stock", yaxis_title="Positive Days (%)", showlegend=False, **layout_base)
    fig4.update_yaxes(range=[0, 100], gridcolor="#e5e7eb")
    charts.append(("Performance Consistency", "Shows the percentage of trading days on which each stock closed higher than the previous day.", plot(fig4, output_type="div", include_plotlyjs=False, config={"responsive": True})))

    return charts


def create_dashboard(symbols=None):
    df = load_data(symbols=symbols)
    metrics = calculate_metrics(df)

    pivot = df.pivot_table(index="date", columns="symbol", values="close")
    correlation = pivot.pct_change().corr()

    claims = generate_claims(metrics, correlation)
    charts = create_charts(df, metrics)

    html = """<!DOCTYPE html>
<html>
<head>
<title>Akatsuki Stock Analyzer</title>
<script src="https://cdn.plot.ly/plotly-2.35.2.min.js"></script>
<style>
* { box-sizing: border-box; }
body { margin: 0; font-family: Arial, Helvetica, sans-serif; background: #f5f5f5; color: #222; }
.container { width: 92%; max-width: 1250px; margin: 0 auto; padding: 24px 0 35px; }
.header { background: #fff; border: 1px solid #ddd; padding: 20px 24px; margin-bottom: 20px; display: flex; align-items: center; gap: 16px; }
.header h1 { margin: 0; font-size: 27px; font-weight: 600; }
.header p { margin: 5px 0 0; color: #666; font-size: 14px; }
.back-link { font-size: 14px; color: #555; text-decoration: none; white-space: nowrap; }
.back-link:hover { color: #000; }
.section-title { font-size: 19px; font-weight: 600; margin: 22px 0 12px; }
.findings-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
.finding-card { background: #fff; border: 1px solid #ddd; padding: 16px 18px; min-height: 115px; }
.finding-card h3 { margin: 0 0 10px; font-size: 16px; font-weight: 600; }
.finding-card p { margin: 0; font-size: 14px; line-height: 1.5; color: #444; }
.metrics-card { background: #fff; border: 1px solid #ddd; padding: 16px 18px; overflow-x: auto; }
.metrics-card table { width: 100%; border-collapse: collapse; font-size: 14px; }
.metrics-card th { text-align: left; padding: 10px; border-bottom: 2px solid #ccc; font-weight: 600; }
.metrics-card td { padding: 10px; border-bottom: 1px solid #e5e5e5; }
.metrics-card tr:last-child td { border-bottom: none; }
.chart-card { background: #fff; border: 1px solid #ddd; padding: 18px; margin-bottom: 16px; }
.chart-card h2 { font-size: 18px; font-weight: 600; margin: 0 0 4px; }
.chart-description { margin: 0 0 12px; font-size: 14px; color: #666; }
.chart-wrapper { width: 100%; min-height: 420px; }
.footer { margin-top: 25px; padding-top: 15px; border-top: 1px solid #ddd; font-size: 12px; color: #777; }
@media (max-width: 700px) {
    .container { width: 95%; padding-top: 15px; }
    .findings-grid { grid-template-columns: 1fr; }
    .header h1 { font-size: 23px; }
    .chart-wrapper { min-height: 350px; }
}
</style>
</head>
<body>
<div class="container">
<div class="header">
    <div>
        <h1>Akatsuki Stock Analyzer</h1>
        <p>Analysis of selected stocks based on return, volatility, movement patterns and consistency.</p>
    </div>
    <a class="back-link" href="/">&larr; Back to selector</a>
</div>
<h2 class="section-title">Key Findings</h2>
<div class="findings-grid">
"""

    for c in claims:
        html += f'<div class="finding-card"><h3>{c["title"]}</h3><p>{c["finding"]}</p></div>\n'

    html += "</div>\n<h2 class=\"section-title\">Stock Metrics</h2>\n<div class=\"metrics-card\">\n"
    html += metrics.to_html(index=False, border=0)
    html += "\n</div>\n<h2 class=\"section-title\">Analysis Charts</h2>\n"

    for title, desc, chart in charts:
        html += f"""<div class="chart-card">
<h2>{title}</h2>
<p class="chart-description">{desc}</p>
<div class="chart-wrapper">{chart}</div>
</div>
"""

    html += """<div class="footer">Akatsuki Stock Analyzer</div>
</div>
</body>
</html>
"""

    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        f.write(html)

    print(f"Dashboard written to {OUTPUT_FILE}")


if __name__ == "__main__":
    create_dashboard()

