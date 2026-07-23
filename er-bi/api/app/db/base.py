"""Import all SQLAlchemy models so Base.metadata is complete."""

from app.db.session import Base
from app.modules.bi.models import BiDbConnector, BiProject
from app.modules.system.models import SysMenu, SysRole, SysUser, role_menu, user_role

__all__ = [
    "Base",
    "BiDbConnector",
    "BiProject",
    "SysMenu",
    "SysRole",
    "SysUser",
    "role_menu",
    "user_role",
]
