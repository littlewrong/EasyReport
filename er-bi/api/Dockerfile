FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app

RUN useradd --create-home --uid 10001 erbi
USER erbi

EXPOSE 5320
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "5320", "--workers", "2"]
