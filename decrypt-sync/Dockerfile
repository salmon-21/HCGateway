FROM python:3.13-slim
WORKDIR /app
RUN pip install --no-cache-dir pymongo cryptography python-dateutil requests
COPY decrypt.py .
CMD ["python", "-u", "decrypt.py"]
