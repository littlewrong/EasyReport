"""ER-BI datasource executable entry point."""

import multiprocessing
import sys


def self_test() -> None:
    """Validate native drivers and frozen multiprocessing support."""
    import psycopg  # noqa: F401
    import psycopg_binary  # noqa: F401
    import pymssql  # noqa: F401

    from app.executors.python import execute
    from app.main import app

    if not any(getattr(route, "path", None) == "/api/status" for route in app.routes):
        raise RuntimeError("/api/status route is missing")
    result = execute("result = sum(range(5))", {}, {})
    if result != 10:
        raise RuntimeError(f"Python datasource self-test returned {result!r}")
    print("er-bi-datasource-api self-test: OK")


def main() -> None:
    if "--self-test" in sys.argv:
        self_test()
        return

    import uvicorn

    from app.core.config import settings
    from app.main import app

    uvicorn.run(app, host=settings.HOST, port=settings.PORT, reload=False, workers=1)


if __name__ == "__main__":
    # Required by the Python datasource executor, which always uses spawn.
    multiprocessing.freeze_support()
    main()
