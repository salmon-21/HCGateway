import sentry_sdk
from flask import Flask
from flask_cors import CORS
import os
from dotenv import load_dotenv
load_dotenv()

try:
    sentry_sdk.init(
        dsn=os.environ['SENTRY_DSN'],
        traces_sample_rate=1.0,
    )
except: pass

app = Flask(__name__)
CORS(app)

from apiVersions.v2 import init_app as init_v2
init_v2(app)

_debug = os.environ.get('APP_DEBUG', '').strip().lower() in ('1', 'true', 'yes', 'on')

app.run(host=os.environ.get('APP_HOST', '0.0.0.0'),
        port=int(os.environ.get('APP_PORT', 6644)),
        debug=_debug)
