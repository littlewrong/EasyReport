from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.core.exceptions import register_exception_handlers
from app.routes import internal_router, public_router, router

app = FastAPI(
    title="ER-BI Datasource API",
    docs_url="/api/docs",
    openapi_url="/api/openapi.json",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:5777",
        "http://127.0.0.1:5777",
        "http://localhost:3020",
        "http://127.0.0.1:3020",
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

register_exception_handlers(app)
app.include_router(router, prefix="/api")
app.include_router(public_router, prefix="/api")
app.include_router(internal_router, prefix="/api")


@app.get("/api/status")
def status():
    return {"status": "ok", "service": "er-bi-datasource"}
