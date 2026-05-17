FROM python:3.13-slim
WORKDIR /app
RUN pip install --no-cache-dir pymongo python-dateutil
COPY process.py .
CMD ["python", "-u", "process.py"]
