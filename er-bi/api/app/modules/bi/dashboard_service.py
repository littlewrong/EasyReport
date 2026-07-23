"""Dashboard (GoView 大屏) business service."""

import json
from typing import Optional

from fastapi import HTTPException
from sqlalchemy.orm import Session

from app.modules.bi.models import BiProject
from app.modules.bi.schemas import ProjectCreate, ProjectEdit, ProjectSave
from app.modules.system.models import SysUser
from app.repositories import project_repo

TIME_FMT = "%Y/%m/%d %H:%M:%S"


def _fmt_time(value) -> Optional[str]:
    return value.strftime(TIME_FMT) if value else None


def _to_list_item(project: BiProject) -> dict:
    """列表项：前端卡片需要 id/projectName/state/indexImage 等。"""
    return {
        "id": project.id,
        "projectName": project.name,
        "state": project.state,
        "indexImage": project.index_image,
        "remarks": project.remarks,
        "createTime": _fmt_time(project.create_time),
    }


def _to_detail(project: BiProject) -> dict:
    """详情：额外带上画布 content。"""
    return {
        **_to_list_item(project),
        "content": project.content,
    }


def _get_or_404(db: Session, project_id: int) -> BiProject:
    project = project_repo.get(db, project_id)
    if not project:
        raise HTTPException(status_code=404, detail="大屏不存在")
    return project


def _name_from_content(content: str) -> Optional[str]:
    """从画布 JSON 中解析 projectName，使列表名与画布标题保持同步。"""
    try:
        data = json.loads(content)
        return (data.get("editCanvasConfig") or {}).get("projectName")
    except (ValueError, AttributeError):
        return None


def create_project(db: Session, user: SysUser, body: ProjectCreate) -> int:
    project = BiProject(
        name=body.projectName,
        remarks=body.remarks,
        index_image=body.indexImage,
        state=-1,
        create_user_id=user.id,
    )
    project_repo.add(db, project)
    db.commit()
    return project.id


def list_projects(db: Session, user: SysUser, page: int, limit: int) -> dict:
    rows, total = project_repo.list_paged(db, user.id, page, limit)
    return {"list": [_to_list_item(p) for p in rows], "count": total}


def get_project_data(db: Session, project_id: int) -> dict:
    return _to_detail(_get_or_404(db, project_id))


def save_project_data(db: Session, body: ProjectSave) -> bool:
    project = _get_or_404(db, body.projectId)
    project.content = body.content
    if body.indexImage is not None:
        project.index_image = body.indexImage
    # 画布标题变更时同步列表显示名
    name = _name_from_content(body.content)
    if name:
        project.name = name
    db.commit()
    return True


def edit_project(db: Session, body: ProjectEdit) -> bool:
    project = _get_or_404(db, body.id)
    if body.projectName is not None:
        project.name = body.projectName
    if body.remarks is not None:
        project.remarks = body.remarks
    if body.indexImage is not None:
        project.index_image = body.indexImage
    db.commit()
    return True


def publish_project(db: Session, project_id: int, state: int) -> bool:
    project = _get_or_404(db, project_id)
    project.state = state
    db.commit()
    return True


def delete_project(db: Session, project_id: int) -> bool:
    project = _get_or_404(db, project_id)
    project_repo.delete(db, project)
    db.commit()
    return True
