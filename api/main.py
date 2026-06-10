import gzip
import io
import os

import sentry_sdk
from flask import Flask
from flask_cors import CORS
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


class GzipRequestMiddleware:
    """Decompress gzip request bodies (the Android app gzips /sync payloads).
    WSGI/Flask only handle compression on responses, not requests."""

    def __init__(self, wsgi):
        self.wsgi = wsgi

    def __call__(self, environ, start_response):
        if environ.get("HTTP_CONTENT_ENCODING", "").lower() == "gzip":
            try:
                body = gzip.decompress(environ["wsgi.input"].read())
            except (OSError, EOFError):
                start_response("400 Bad Request", [("Content-Type", "application/json")])
                return [b'{"error": "malformed gzip body"}']
            environ["wsgi.input"] = io.BytesIO(body)
            environ["CONTENT_LENGTH"] = str(len(body))
            del environ["HTTP_CONTENT_ENCODING"]
        return self.wsgi(environ, start_response)


app.wsgi_app = GzipRequestMiddleware(app.wsgi_app)

# Production serving is gunicorn (see Dockerfile) pointing at main:app.
# Direct execution keeps the dev server for local debugging only.
if __name__ == '__main__':
    _debug = os.environ.get('APP_DEBUG', '').strip().lower() in ('1', 'true', 'yes', 'on')
    app.run(host=os.environ.get('APP_HOST', '0.0.0.0'),
            port=int(os.environ.get('APP_PORT', 6644)),
            debug=_debug)
