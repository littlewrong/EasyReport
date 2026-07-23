"""ER-BI API executable entry point.

Keep imports inside ``main`` so PyInstaller's multiprocessing bootstrap can run
before the application is imported in a spawned child process.
"""

import multiprocessing
import sys


def self_test() -> None:
    """Validate imports that are commonly missed by frozen builds."""
    import sqlalchemy.dialects.mysql.pymysql  # noqa: F401

    from app.main import app

    if not any(getattr(route, "path", None) == "/api/status" for route in app.routes):
        raise RuntimeError("/api/status route is missing")
    print("er-bi-api self-test: OK")


def main() -> None:
    if "--self-test" in sys.argv:
        self_test()
        return

    import uvicorn

    from app.core.config import settings
    from app.main import app

    uvicorn.run(app, host=settings.HOST, port=settings.PORT, reload=False, workers=1)


if __name__ == "__main__":
    multiprocessing.freeze_support()
    main()
