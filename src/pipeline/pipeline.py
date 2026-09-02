from src.transfrom.transform import transform
from src.eda.generate_charts import create_dashboard
def start_etl():
    transform()
    create_dashboard()
    loader = TradingLoader()
    loader.load()
