from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.v1.router import api_router
from app.core.exceptions import register_exception_handlers

app = FastAPI(title="ER-BI API", docs_url="/api/docs", openapi_url="/api/openapi.json")

app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:5777",
        "http://127.0.0.1:5777",
        # BI 大屏设计器（bi_designer）直连兜底，开发态默认经 vite proxy 同源
        "http://localhost:3020",
        "http://127.0.0.1:3020",
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

register_exception_handlers(app)
app.include_router(api_router, prefix="/api")


@app.get("/api/status")
def status():
    return {"status": "ok"}
