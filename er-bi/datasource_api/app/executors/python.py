"""Process-isolated Python datasource execution.

The AST policy is compatibility protection, not the security boundary. The
separate child process, timeout and container limits are the primary boundary.
"""

import ast
import multiprocessing
import os
import queue
import time
from typing import Any, Optional

from fastapi import HTTPException

from app.core.config import settings
from app.executors.sql import query


def _validate(code: str) -> ast.Module:
    try:
        tree = ast.parse(code, mode="exec")
    except SyntaxError as exc:
        raise ValueError(f"Python代码语法错误：{exc}") from exc
    blocked = (
        ast.AsyncFunctionDef,
        ast.ClassDef,
        ast.Global,
        ast.Import,
        ast.ImportFrom,
        ast.Nonlocal,
    )
    for node in ast.walk(tree):
        if isinstance(node, blocked):
            raise ValueError("Python数据源不允许 import、class、async、global 或 nonlocal")
    return tree


def _public_connector(connector: dict) -> dict:
    return {
        key: connector.get(key)
        for key in ("id", "name", "dbType", "host", "port", "database", "username")
    }


def _apply_resource_limits(memory_limit_mb: int) -> None:
    try:
        import resource

        memory_bytes = memory_limit_mb * 1024 * 1024
        resource.setrlimit(resource.RLIMIT_AS, (memory_bytes, memory_bytes))
        resource.setrlimit(resource.RLIMIT_NOFILE, (32, 32))
        resource.setrlimit(resource.RLIMIT_NPROC, (16, 16))
    except (ImportError, OSError, ValueError):
        # Windows has no resource module; Docker memory/pid limits remain active.
        pass


def _worker(
    code: str,
    public_connector: dict,
    params: dict,
    result_queue,
    query_pipe,
    memory_limit_mb: int,
) -> None:
    _apply_resource_limits(memory_limit_mb)
    # User code must not inherit JWT, metadata DB or internal service secrets.
    os.environ.clear()
    try:
        tree = _validate(code)

        def run_query(sql: str, query_params: Any = None) -> Any:
            query_pipe.send(("query", sql, query_params))
            success, response = query_pipe.recv()
            if not success:
                raise RuntimeError(response)
            return response

        safe_builtins = {
            "abs": abs,
            "all": all,
            "any": any,
            "bool": bool,
            "dict": dict,
            "enumerate": enumerate,
            "filter": filter,
            "float": float,
            "int": int,
            "len": len,
            "list": list,
            "map": map,
            "max": max,
            "min": min,
            "range": range,
            "round": round,
            "set": set,
            "sorted": sorted,
            "str": str,
            "sum": sum,
            "tuple": tuple,
            "zip": zip,
        }
        scope: dict[str, Any] = {}
        exec(
            compile(tree, "<python-datasource>", "exec"),
            {"__builtins__": safe_builtins},
            scope,
        )
        main_func = scope.get("main")
        result = (
            main_func(public_connector, run_query, params)
            if callable(main_func)
            else scope.get("result")
        )
        result_queue.put((True, result))
    except BaseException as exc:
        result_queue.put((False, f"{type(exc).__name__}: {exc}"))
    finally:
        query_pipe.close()


def execute(code: str, connector: dict, params: Optional[dict] = None) -> Any:
    # Validate in the parent so syntax errors return without spawning a worker.
    try:
        _validate(code)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    context = multiprocessing.get_context("spawn")
    result_queue = context.Queue(maxsize=1)
    parent_query_pipe, child_query_pipe = context.Pipe()
    process = context.Process(
        target=_worker,
        args=(
            code,
            _public_connector(connector),
            params or {},
            result_queue,
            child_query_pipe,
            settings.PYTHON_MEMORY_LIMIT_MB,
        ),
        daemon=True,
    )
    process.start()
    deadline = time.monotonic() + settings.PYTHON_TIMEOUT_SECONDS
    while process.is_alive() and time.monotonic() < deadline:
        remaining = max(deadline - time.monotonic(), 0)
        if parent_query_pipe.poll(min(0.05, remaining)):
            try:
                message = parent_query_pipe.recv()
                if (
                    not isinstance(message, tuple)
                    or len(message) != 3
                    or message[0] != "query"
                    or not isinstance(message[1], str)
                ):
                    parent_query_pipe.send((False, "无效的SQL查询请求"))
                    continue
                try:
                    data = query(connector, message[1], message[2])
                    parent_query_pipe.send((True, data))
                except BaseException as exc:
                    parent_query_pipe.send((False, f"{type(exc).__name__}: {exc}"))
            except (EOFError, BrokenPipeError):
                break
        process.join(0)
    if process.is_alive():
        process.terminate()
        process.join(2)
        child_query_pipe.close()
        parent_query_pipe.close()
        result_queue.close()
        raise HTTPException(status_code=408, detail="Python执行超时，任务已终止")
    try:
        success, result = result_queue.get_nowait()
    except queue.Empty as exc:
        raise HTTPException(status_code=400, detail="Python执行进程异常退出") from exc
    finally:
        child_query_pipe.close()
        parent_query_pipe.close()
        result_queue.close()
    if not success:
        raise HTTPException(status_code=400, detail=f"Python执行失败：{result}")
    return result
