from typing import Optional

from fastapi import HTTPException
from sqlalchemy.orm import Session

from app.modules.system.menu_tree import build_tree, to_admin_item, to_route
from app.modules.system.models import SysMenu, SysUser
from app.modules.system.permissions import is_super
from app.modules.system.schemas import MenuBody
from app.repositories import menu_repo


def list_admin_menus(db: Session) -> list[dict]:
    menus = menu_repo.list_all(db)
    grouped = build_tree(menus)
    return [to_admin_item(menu, grouped) for menu in grouped.get(0, [])]


def name_exists(db: Session, name: str, menu_id: Optional[int] = None) -> bool:
    return menu_repo.name_exists(db, name, exclude_id=menu_id)


def path_exists(db: Session, path: str, menu_id: Optional[int] = None) -> bool:
    return menu_repo.path_exists(db, path, exclude_id=menu_id)


def create_menu(db: Session, body: MenuBody) -> int:
    menu = SysMenu(
        name=body.name,
        path=body.path,
        component=body.component,
        redirect=body.redirect,
        pid=body.pid or 0,
        type=body.type,
        auth_code=body.authCode,
        status=body.status,
        meta=body.meta,
    )
    menu_repo.add(db, menu)
    db.commit()
    return menu.id


def update_menu(db: Session, menu_id: int, body: MenuBody) -> int:
    menu = menu_repo.get(db, menu_id)
    if not menu:
        raise HTTPException(status_code=404, detail="菜单不存在")
    menu.name = body.name
    menu.path = body.path
    menu.component = body.component
    menu.redirect = body.redirect
    menu.pid = body.pid or 0
    menu.type = body.type
    menu.auth_code = body.authCode
    menu.status = body.status
    menu.meta = body.meta
    db.commit()
    return menu_id


def delete_menu(db: Session, menu_id: int) -> int:
    menu = menu_repo.get(db, menu_id)
    if not menu:
        raise HTTPException(status_code=404, detail="菜单不存在")
    if menu_repo.has_children(db, menu_id):
        raise HTTPException(status_code=400, detail="存在子菜单，无法删除")
    menu_repo.delete(db, menu)
    db.commit()
    return menu_id


def get_visible_routes(db: Session, user: SysUser) -> list[dict]:
    if is_super(user):
        menus = menu_repo.list_active(db)
    else:
        seen: dict[int, SysMenu] = {}
        for role in user.roles:
            if role.status != 1:
                continue
            for menu in role.menus:
                if menu.status == 1:
                    seen[menu.id] = menu

        all_menus = {menu.id: menu for menu in menu_repo.list_all(db)}
        for menu in list(seen.values()):
            pid = menu.pid or 0
            while pid and pid not in seen:
                parent = all_menus.get(pid)
                if not parent or parent.status != 1:
                    break
                seen[parent.id] = parent
                pid = parent.pid or 0
        menus = list(seen.values())

    menus = [menu for menu in menus if menu.type != "button"]
    grouped = build_tree(menus)
    return [to_route(menu, grouped) for menu in grouped.get(0, [])]
