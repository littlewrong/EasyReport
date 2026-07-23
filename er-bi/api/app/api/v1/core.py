from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.deps import get_current_user, get_db
from app.core.response import ok
from app.modules.auth.service import user_payload
from app.modules.system import menu_service
from app.modules.system.models import SysUser

router = APIRouter(tags=["core"])


@router.get("/user/info")
def get_user_info(user: SysUser = Depends(get_current_user)):
    return ok(user_payload(user))


@router.get("/menu/all")
def get_all_menus(
    user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)
):
    return ok(menu_service.get_visible_routes(db, user))
