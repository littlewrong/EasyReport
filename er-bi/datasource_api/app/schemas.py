from typing import Any, Optional

from pydantic import BaseModel


class HttpDatasourceBody(BaseModel):
    name: str
    requestMethod: str = "GET"
    requestUrl: str
    requestHeaders: Optional[dict] = None
    requestBody: Optional[str] = None
    status: int = 1
    remark: Optional[str] = None


class HttpDatasourceTestBody(HttpDatasourceBody):
    params: Optional[dict] = None


class SqlDatasourceBody(BaseModel):
    name: str
    connectorId: int
    sqlContent: str
    status: int = 1
    remark: Optional[str] = None


class SqlDatasourceTestBody(SqlDatasourceBody):
    params: Optional[dict] = None


class PythonDatasourceBody(BaseModel):
    name: str
    connectorId: int
    pythonCode: str
    status: int = 1
    remark: Optional[str] = None


class PythonDatasourceTestBody(PythonDatasourceBody):
    params: Optional[dict] = None


class PythonDatasourcePublish(BaseModel):
    id: int
    state: int


class ConnectorProfile(BaseModel):
    id: Optional[int] = None
    name: str
    dbType: str
    host: str
    port: int
    database: str
    username: str
    password: Optional[str] = None
    extra: Optional[dict[str, Any]] = None
    status: int = 1
