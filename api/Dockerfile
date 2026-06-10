# Use the official Python image from the DockerHub
FROM python:3.13-slim

# Set the working directory inside the container
WORKDIR /app

# Copy the requirements.txt file to the container
COPY requirements.txt .

# Install the Python dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Copy the rest of the application code to the container
COPY . .

# gunicorn instead of the Flask dev server: the dev server is single-threaded,
# so one slow /fetch would block the device's /sync. 1 worker keeps the
# in-process caches (streak, sleep-count) coherent; threads give concurrency.
CMD ["gunicorn", "--bind", "0.0.0.0:6644", "--workers", "1", "--threads", "4", "--timeout", "120", "main:app"]
