from typing import Optional

from fastapi import HTTPException
from sqlalchemy.orm import Session

from app.modules.system.menu_tree import TIME_FMT
from app.modules.system.models import SysRole
from app.modules.system.schemas import RoleBody
from app.repositories import role_repo


def serialize_role(role: SysRole) -> dict:
    return {
        "id": role.id,
        "name": role.name,
        "status": role.status,
        "remark": role.remark,
        "permissions": [menu.id for menu in role.menus],
        "createTime": role.create_time.strftime(TIME_FMT)
        if role.create_time
        else None,
    }


def list_roles(
    db: Session,
    *,
    page: int,
    page_size: int,
    name: Optional[str] = None,
    role_id: Optional[str] = None,
    remark: Optional[str] = None,
    status: Optional[int] = None,
    start_time: Optional[str] = None,
    end_time: Optional[str] = None,
) -> tuple[list[dict], int]:
    roles = role_repo.list_roles(
        db,
        name=name,
        role_id=role_id,
        remark=remark,
        status=status,
        start_time=start_time,
        end_time=end_time,
    )
    total = len(roles)
    offset = (page - 1) * page_size
    return [serialize_role(role) for role in roles[offset : offset + page_size]], total


def create_role(db: Session, body: RoleBody) -> int:
    if role_repo.name_exists(db, body.name):
        raise HTTPException(status_code=400, detail="角色名称已存在")
    role = SysRole(name=body.name, status=body.status, remark=body.remark)
    role_repo.set_permissions(db, role, body.permissions)
    role_repo.add(db, role)
    db.commit()
    return role.id


def update_role(db: Session, role_id: int, body: RoleBody) -> int:
    role = role_repo.get(db, role_id)
    if not role:
        raise HTTPException(status_code=404, detail="角色不存在")
    if role_repo.name_exists(db, body.name, exclude_id=role_id):
        raise HTTPException(status_code=400, detail="角色名称已存在")
    role.name = body.name
    role.status = body.status
    role.remark = body.remark
    role_repo.set_permissions(db, role, body.permissions)
    db.commit()
    return role_id


def delete_role(db: Session, role_id: int) -> int:
    role = role_repo.get(db, role_id)
    if not role:
        raise HTTPException(status_code=404, detail="角色不存在")
    role_repo.delete(db, role)
    db.commit()
    return role_id
