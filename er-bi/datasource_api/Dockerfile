FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app

RUN useradd --create-home --uid 10002 datasource
USER datasource

EXPOSE 5321
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "5321", "--workers", "2"]
