"""Menu tree and frontend route serialization helpers."""

from app.modules.system.models import SysMenu

TIME_FMT = "%Y/%m/%d %H:%M:%S"


def _sort_key(menu: SysMenu):
    meta = menu.meta or {}
    return meta.get("order") or 0


def build_tree(menus: list[SysMenu]) -> dict[int, list[SysMenu]]:
    grouped: dict[int, list[SysMenu]] = {}
    for menu in menus:
        grouped.setdefault(menu.pid or 0, []).append(menu)
    for children in grouped.values():
        children.sort(key=_sort_key)
    return grouped


def to_route(menu: SysMenu, grouped: dict[int, list[SysMenu]]) -> dict:
    route: dict = {"name": menu.name, "path": menu.path or ""}
    if menu.component:
        route["component"] = menu.component
    if menu.redirect:
        route["redirect"] = menu.redirect
    if menu.meta:
        route["meta"] = menu.meta
    if menu.type == "link":
        route.setdefault("meta", {})

    children = [
        to_route(child, grouped)
        for child in grouped.get(menu.id, [])
        if child.status == 1 and child.type != "button"
    ]
    if children:
        route["children"] = children
    return route


def to_admin_item(menu: SysMenu, grouped: dict[int, list[SysMenu]]) -> dict:
    item = {
        "id": menu.id,
        "pid": menu.pid or 0,
        "name": menu.name,
        "path": menu.path,
        "component": menu.component,
        "redirect": menu.redirect,
        "type": menu.type,
        "authCode": menu.auth_code,
        "status": menu.status,
        "meta": menu.meta or {},
        "createTime": menu.create_time.strftime(TIME_FMT)
        if menu.create_time
        else None,
    }
    children = [to_admin_item(child, grouped) for child in grouped.get(menu.id, [])]
    if children:
        item["children"] = children
    return item
