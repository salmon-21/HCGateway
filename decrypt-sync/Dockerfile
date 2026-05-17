FROM python:3.13-slim
WORKDIR /app
RUN pip install --no-cache-dir pymongo python-dateutil
COPY decrypt.py .
CMD ["python", "-u", "decrypt.py"]
