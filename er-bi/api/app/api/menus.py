from typing import Optional

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.deps import get_current_user, get_db
from app.core.response import ok
from app.modules.system import menu_service
from app.modules.system.schemas import MenuBody

router = APIRouter(
    prefix="/system/menu", tags=["system-menu"], dependencies=[Depends(get_current_user)]
)


@router.get("/list")
def menu_list(db: Session = Depends(get_db)):
    return ok(menu_service.list_admin_menus(db))


@router.get("/name-exists")
def name_exists(name: str, id: Optional[int] = None, db: Session = Depends(get_db)):
    return ok(menu_service.name_exists(db, name, menu_id=id))


@router.get("/path-exists")
def path_exists(path: str, id: Optional[int] = None, db: Session = Depends(get_db)):
    return ok(menu_service.path_exists(db, path, menu_id=id))


@router.post("")
def create_menu(body: MenuBody, db: Session = Depends(get_db)):
    return ok(menu_service.create_menu(db, body))


@router.put("/{menu_id}")
def update_menu(menu_id: int, body: MenuBody, db: Session = Depends(get_db)):
    return ok(menu_service.update_menu(db, menu_id, body))


@router.delete("/{menu_id}")
def delete_menu(menu_id: int, db: Session = Depends(get_db)):
    return ok(menu_service.delete_menu(db, menu_id))
