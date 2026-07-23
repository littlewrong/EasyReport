from typing import Optional

from pydantic import BaseModel, Field


class UserBody(BaseModel):
    username: str
    password: Optional[str] = None
    realName: str = ""
    homePath: Optional[str] = None
    status: int = 1
    remark: Optional[str] = None
    roles: list[int] = Field(default_factory=list)


class RoleBody(BaseModel):
    name: str
    status: int = 1
    remark: Optional[str] = None
    permissions: list[int] = Field(default_factory=list)


class MenuBody(BaseModel):
    name: str
    path: Optional[str] = None
    component: Optional[str] = None
    redirect: Optional[str] = None
    pid: int = 0
    type: str = "menu"
    authCode: Optional[str] = None
    status: int = 1
    meta: Optional[dict] = None
