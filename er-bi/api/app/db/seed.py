"""Database creation and seed data."""

from sqlalchemy import create_engine, select, text
from sqlalchemy.orm import Session

from app.core.config import settings
from app.core.security import hash_password
from app.db.session import SessionLocal
from app.modules.system.models import SysMenu, SysRole, SysUser

SEED_MENUS = [
    (
        1,
        0,
        "Dashboard",
        "/dashboard",
        None,
        "/workspace",
        "catalog",
        None,
        {
            "order": -1,
            "title": "page.dashboard.title",
            "icon": "lucide:layout-dashboard",
        },
    ),
    (
        102,
        1,
        "Workspace",
        "/workspace",
        "/dashboard/workspace/index",
        None,
        "menu",
        None,
        {"title": "page.dashboard.workspace", "icon": "carbon:workspace"},
    ),
    (
        3000,
        0,
        "BigScreenDesign",
        "/bi",
        None,
        "/bi/designer",
        "catalog",
        None,
        {"order": 10, "title": "大屏设计", "icon": "carbon:dashboard"},
    ),
    (
        3001,
        0,
        "BiDatasource",
        "/datasource",
        None,
        "/datasource/db-connector",
        "catalog",
        "BI:Datasource:List",
        {"order": 9, "title": "数据源管理", "icon": "carbon:data-base"},
    ),
    (
        3002,
        3000,
        "BiDesigner",
        "/bi/designer",
        "IFrameView",
        None,
        "link",
        "BI:Designer:Open",
        {
            "order": 10,
            "title": "大屏设计",
            "icon": "carbon:chart-network",
            "link": "http://localhost:3020/index.html",
            "openInNewWindow": True,
            # 打开新窗口时由前端动态带上当前登录 token
            "attachToken": True,
        },
    ),
    (
        3003,
        3001,
        "BiDbConnector",
        "/datasource/db-connector",
        "/bi/datasource/db-connector/list",
        None,
        "menu",
        "BI:Datasource:DbConnector",
        {"order": 0, "title": "DB连接器", "icon": "carbon:data-base-alt"},
    ),
    (
        3004,
        3001,
        "BiHttpDatasource",
        "/datasource/http",
        "/bi/datasource/http-source/list",
        None,
        "menu",
        "BI:Datasource:Http",
        {"order": 10, "title": "HTTP数据源", "icon": "carbon:api"},
    ),
    (
        3005,
        3001,
        "BiSqlDatasource",
        "/datasource/sql",
        "/bi/datasource/sql-source/list",
        None,
        "menu",
        "BI:Datasource:Sql",
        {"order": 20, "title": "SQL数据源", "icon": "carbon:sql"},
    ),
    (
        3007,
        3001,
        "BiPythonDatasource",
        "/datasource/python",
        "/bi/datasource/python-source/list",
        None,
        "menu",
        "BI:Datasource:Python",
        {"order": 30, "title": "Python数据源", "icon": "carbon:logo-python"},
    ),
    (
        3006,
        3000,
        "BiDatasourceLegacy",
        "/bi/datasource",
        None,
        "/datasource/db-connector",
        "menu",
        None,
        {"title": "数据源管理", "hideInMenu": True, "hideInBreadcrumb": True},
    ),
    (
        2,
        0,
        "System",
        "/system",
        None,
        None,
        "catalog",
        None,
        {"order": 9997, "title": "系统管理", "icon": "carbon:settings"},
    ),
    (
        201,
        2,
        "SystemMenu",
        "/system/menu",
        "/system/menu/list",
        None,
        "menu",
        "System:Menu:List",
        {"title": "菜单管理", "icon": "carbon:menu"},
    ),
    (20101, 201, "SystemMenuCreate", None, None, None, "button", "System:Menu:Create", {"title": "新增"}),
    (20102, 201, "SystemMenuEdit", None, None, None, "button", "System:Menu:Edit", {"title": "修改"}),
    (20103, 201, "SystemMenuDelete", None, None, None, "button", "System:Menu:Delete", {"title": "删除"}),
    (
        202,
        2,
        "SystemRole",
        "/system/role",
        "/system/role/list",
        None,
        "menu",
        "System:Role:List",
        {"title": "角色管理", "icon": "carbon:user-role"},
    ),
    (20201, 202, "SystemRoleCreate", None, None, None, "button", "System:Role:Create", {"title": "新增"}),
    (20202, 202, "SystemRoleEdit", None, None, None, "button", "System:Role:Edit", {"title": "修改"}),
    (20203, 202, "SystemRoleDelete", None, None, None, "button", "System:Role:Delete", {"title": "删除"}),
    (
        203,
        2,
        "SystemUser",
        "/system/user",
        "/system/user/list",
        None,
        "menu",
        "System:User:List",
        {"title": "用户管理", "icon": "carbon:user"},
    ),
    (20301, 203, "SystemUserCreate", None, None, None, "button", "System:User:Create", {"title": "新增"}),
    (20302, 203, "SystemUserEdit", None, None, None, "button", "System:User:Edit", {"title": "修改"}),
    (20303, 203, "SystemUserDelete", None, None, None, "button", "System:User:Delete", {"title": "删除"}),
]


def create_database() -> None:
    server_engine = create_engine(settings.server_url)
    with server_engine.connect() as conn:
        conn.execute(
            text(
                f"CREATE DATABASE IF NOT EXISTS `{settings.DB_NAME}` "
                "CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci"
            )
        )
        conn.commit()
    server_engine.dispose()
    print(f"[init] 数据库 {settings.DB_NAME} 就绪")


def seed_system_data(db: Session) -> None:
    if db.scalar(select(SysUser)) is not None:
        print("[init] 已存在数据，跳过种子填充")
        return

    menus = [
        SysMenu(
            id=mid,
            pid=pid,
            name=name,
            path=path,
            component=component,
            redirect=redirect,
            type=menu_type,
            auth_code=auth_code,
            status=1,
            meta=meta,
        )
        for mid, pid, name, path, component, redirect, menu_type, auth_code, meta in SEED_MENUS
    ]
    db.add_all(menus)
    db.flush()

    non_button_menus = [menu for menu in menus if menu.type != "button"]
    dashboard_menus = [menu for menu in menus if menu.id in (1, 102)]

    super_role = SysRole(id=1, name="super", remark="超级管理员，拥有全部权限", menus=menus)
    admin_role = SysRole(id=2, name="admin", remark="管理员", menus=non_button_menus)
    user_role = SysRole(id=3, name="user", remark="普通用户", menus=dashboard_menus)
    db.add_all([super_role, admin_role, user_role])

    db.add_all(
        [
            SysUser(
                username="admin",
                password=hash_password("123456"),
                real_name="超级管理员",
                home_path="/workspace",
                roles=[super_role],
            ),
            SysUser(
                username="jack",
                password=hash_password("123456"),
                real_name="Jack",
                home_path="/workspace",
                roles=[user_role],
            ),
        ]
    )
    db.commit()
    print("[init] 种子数据填充完成（admin/123456, jack/123456）")


def seed() -> None:
    db = SessionLocal()
    try:
        seed_system_data(db)
    finally:
        db.close()
