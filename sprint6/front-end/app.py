import os
from flask import Flask, render_template
from dotenv import load_dotenv

load_dotenv()

app = Flask(__name__, static_folder='static', template_folder='templates')

BACKEND_URL = os.getenv('BACKEND_URL', 'http://localhost:8082')
AUTH_URL = os.getenv('AUTH_URL', 'http://localhost:3000')

@app.context_processor
def inject_urls():
    return dict(BACKEND_URL=BACKEND_URL, AUTH_URL=AUTH_URL)

@app.route('/')
def index():
    return render_template('login.html')

@app.route('/login')
def login_page():
    return render_template('login.html')

@app.route('/dashboard')
def dashboard_page():
    return render_template('dashboard.html')

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=True)
