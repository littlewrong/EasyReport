from app.modules.system.models import SysUser


def is_super(user: SysUser) -> bool:
    return any(role.name == "super" for role in user.roles)
