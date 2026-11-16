from typing import Optional

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.deps import get_current_user, get_db
from app.core.response import ok, page_ok
from app.modules.system import role_service
from app.modules.system.schemas import RoleBody

router = APIRouter(
    prefix="/system/role", tags=["system-role"], dependencies=[Depends(get_current_user)]
)


@router.get("/list")
def role_list(
    page: int = 1,
    pageSize: int = 20,
    name: Optional[str] = None,
    id: Optional[str] = None,
    remark: Optional[str] = None,
    status: Optional[int] = None,
    startTime: Optional[str] = None,
    endTime: Optional[str] = None,
    db: Session = Depends(get_db),
):
    items, total = role_service.list_roles(
        db,
        page=page,
        page_size=pageSize,
        name=name,
        role_id=id,
        remark=remark,
        status=status,
        start_time=startTime,
        end_time=endTime,
    )
    return page_ok(items, total)


@router.post("")
def create_role(body: RoleBody, db: Session = Depends(get_db)):
    return ok(role_service.create_role(db, body))


@router.put("/{role_id}")
def update_role(role_id: int, body: RoleBody, db: Session = Depends(get_db)):
    return ok(role_service.update_role(db, role_id, body))


@router.delete("/{role_id}")
def delete_role(role_id: int, db: Session = Depends(get_db)):
    return ok(role_service.delete_role(db, role_id))
