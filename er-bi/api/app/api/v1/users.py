from typing import Optional

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.deps import get_current_user, get_db
from app.core.response import ok, page_ok
from app.modules.system import user_service
from app.modules.system.models import SysUser
from app.modules.system.schemas import UserBody

router = APIRouter(
    prefix="/system/user", tags=["system-user"], dependencies=[Depends(get_current_user)]
)


@router.get("/list")
def user_list(
    page: int = 1,
    pageSize: int = 20,
    username: Optional[str] = None,
    realName: Optional[str] = None,
    name: Optional[str] = None,
    status: Optional[int] = None,
    startTime: Optional[str] = None,
    endTime: Optional[str] = None,
    db: Session = Depends(get_db),
):
    items, total = user_service.list_users(
        db,
        page=page,
        page_size=pageSize,
        username=username,
        real_name=realName,
        name=name,
        status=status,
        start_time=startTime,
        end_time=endTime,
    )
    return page_ok(items, total)


@router.post("")
def create_user(body: UserBody, db: Session = Depends(get_db)):
    return ok(user_service.create_user(db, body))


@router.put("/{user_id}")
def update_user(user_id: int, body: UserBody, db: Session = Depends(get_db)):
    return ok(user_service.update_user(db, user_id, body))


@router.delete("/{user_id}")
def delete_user(
    user_id: int,
    db: Session = Depends(get_db),
    current: SysUser = Depends(get_current_user),
):
    return ok(user_service.delete_user(db, user_id, current))
