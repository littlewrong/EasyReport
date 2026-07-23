"""Pydantic schemas owned by the ER-BI control process."""

from typing import Optional

from pydantic import BaseModel


class ProjectCreate(BaseModel):
    projectName: str
    remarks: Optional[str] = None
    indexImage: Optional[str] = None


class ProjectSave(BaseModel):
    projectId: int
    content: str
    indexImage: Optional[str] = None


class ProjectEdit(BaseModel):
    id: int
    projectName: Optional[str] = None
    remarks: Optional[str] = None
    indexImage: Optional[str] = None


class ProjectPublish(BaseModel):
    id: int
    state: int


class DbConnectorBody(BaseModel):
    name: str
    dbType: str
    host: str
    port: int
    database: str
    username: str
    password: Optional[str] = None
    extra: Optional[dict] = None
    status: int = 1
    remark: Optional[str] = None
