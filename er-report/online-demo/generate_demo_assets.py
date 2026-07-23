import json
import re
from pathlib import Path
from xml.sax.saxutils import escape as xml_escape


ROOT = Path(__file__).resolve().parents[2]
WEBSITE_DIR = ROOT / "website"
REPORTDATA_DIR = WEBSITE_DIR / "reportdata"
REPORT_DIR = ROOT / "er-report" / "online-demo" / "reports"
INDEX_FILE = ROOT / "er-report" / "online-demo" / "demo-index.json"
PREVIEW_HTML = WEBSITE_DIR / "preview.html"

DATA_BASE_URL = "http://localhost/reportdata"
REPORT_BASE_URL = "https://www.easyreport.cn:18083/easyreport"


def col_name(index):
    result = ""
    while index:
        index, remainder = divmod(index - 1, 26)
        result = chr(65 + remainder) + result
    return result


def attr(value):
    return xml_escape(str(value), {'"': "&quot;"})


def cdata(value):
    return str(value).replace("]]>", "]]]]><![CDATA[>")


def border():
    return (
        '<left-border width="1" style="solid" color="0,0,0"/>'
        '<right-border width="1" style="solid" color="0,0,0"/>'
        '<top-border width="1" style="solid" color="0,0,0"/>'
        '<bottom-border width="1" style="solid" color="0,0,0"/>'
    )


def style(font_size=10, bold=False, bgcolor=None, forecolor="0,0,0", align="center",
          valign="middle", wrap=False, fmt=None, with_border=True):
    parts = [
        f'font-size="{font_size}"',
        f'forecolor="{attr(forecolor)}"',
        'font-family="宋体"',
        f'align="{align}"',
        f'valign="{valign}"',
    ]
    if bold:
        parts.append('bold="true"')
    if bgcolor:
        parts.append(f'bgcolor="{attr(bgcolor)}"')
    if wrap:
        parts.append('wrap-compute="true"')
    if fmt:
        parts.append(f'format="{attr(fmt)}"')
    inner = border() if with_border else ""
    return f"<cell-style {' '.join(parts)}>{inner}</cell-style>"


def condition_style():
    return (
        '<cell-style for-condition="true" font-size-scope="cell" forecolor-scope="cell" '
        'font-family-scope="cell" bgcolor-scope="cell" bold-scope="cell" '
        'italic-scope="cell" underline-scope="cell" align-scope="cell" '
        'valign-scope="cell"></cell-style>'
    )


def cell_attrs(name, row, col, expand="None", col_span=None, row_span=None,
               left_cell=None, top_cell=None):
    parts = [f'expand="{expand}"', f'name="{name}"', f'row="{row}"', f'col="{col}"']
    if col_span:
        parts.append(f'col-span="{col_span}"')
    if row_span:
        parts.append(f'row-span="{row_span}"')
    if left_cell:
        parts.append(f'left-cell="{left_cell}"')
    if top_cell:
        parts.append(f'top-cell="{top_cell}"')
    return " ".join(parts)


def simple_cell(row, col, value, expand="None", col_span=None, row_span=None,
                left_cell=None, top_cell=None, style_xml=None, name=None, extra_xml=""):
    name = name or f"{col_name(col)}{row}"
    style_xml = style_xml or style()
    return (
        f'<cell {cell_attrs(name, row, col, expand, col_span, row_span, left_cell, top_cell)}>'
        f'<simple-value><![CDATA[{cdata(value)}]]></simple-value>'
        f'{style_xml}{extra_xml}</cell>'
    )


def expr_cell(row, col, expression, expand="None", col_span=None, row_span=None,
              left_cell=None, top_cell=None, style_xml=None, name=None, extra_xml=""):
    name = name or f"{col_name(col)}{row}"
    style_xml = style_xml or style()
    return (
        f'<cell {cell_attrs(name, row, col, expand, col_span, row_span, left_cell, top_cell)}>'
        f'<expression-value><![CDATA[{cdata(expression)}]]></expression-value>'
        f'{style_xml}{extra_xml}</cell>'
    )


def dataset_cell(row, col, dataset, prop, aggregate="select", expand="None",
                 left_cell=None, top_cell=None, style_xml=None, name=None,
                 order="none", mapping_type="simple", row_span=None,
                 col_span=None, use_index=False, index=None, extra_xml=""):
    name = name or f"{col_name(col)}{row}"
    style_xml = style_xml or style()
    index_xml = ""
    if use_index:
        index_xml = ' use-index="true"'
        if index is not None:
            index_xml += f' index="{index}"'
    return (
        f'<cell {cell_attrs(name, row, col, expand, col_span, row_span, left_cell, top_cell)}>'
        f'<dataset-value dataset-name="{attr(dataset)}" aggregate="{aggregate}" '
        f'property="{attr(prop)}" order="{order}" mapping-type="{mapping_type}"{index_xml}/>'
        f'{style_xml}{extra_xml}</cell>'
    )


def rows_xml(count, heights=None, bands=None):
    heights = heights or {}
    bands = bands or {}
    rows = []
    for i in range(1, count + 1):
        band = f' band="{bands[i]}"' if i in bands else ""
        rows.append(f'<row row-number="{i}" height="{heights.get(i, 22)}"{band}/>')
    return "".join(rows)


def columns_xml(widths):
    return "".join(
        f'<column col-number="{i}" width="{width}"/>'
        for i, width in enumerate(widths, start=1)
    )


def datasource_xml(slug, fields, dataset="ds"):
    url = f"{DATA_BASE_URL}/{slug}.html"
    field_xml = "".join(f'<field name="{attr(field)}"/>' for field in fields)
    return (
        '<datasource name="在线演示API" type="http">'
        f'<dataset name="{dataset}" type="http" url="{attr(url)}" method="GET" dataPath="data.rows">'
        '<parameter name="page" type="String" default-value="1"/>'
        '<parameter name="page_size" type="String" default-value="50"/>'
        '<parameter name="start" type="String" default-value="2026-01-01"/>'
        '<parameter name="end" type="String" default-value="2026-12-31"/>'
        f'{field_xml}</dataset></datasource>'
    )


def paper_xml(column_enabled=False, column_count=2, paging="fitpage", fixrows=0,
              api_paging=False, api_summary=False, api_dataset="ds"):
    extras = ""
    if api_paging:
        extras += (
            ' api-paging-enabled="true" api-default-page-size="50" '
            'api-max-page-size="5000" api-dataset-name="ds" '
            'api-field-mapping="序号->序号,项目->项目,金额->金额,状态->状态"'
        )
    if api_summary:
        extras += (
            ' api-summary-enabled="true" api-summary-dataset-name="summary" '
            'api-summary-field-mapping="金额->金额" api-summary-label="合计"'
        )
    column = ""
    if column_enabled:
        column = f' column-count="{column_count}" column-margin="18"'
    return (
        '<paper type="A4" left-margin="90" right-margin="90" '
        f'top-margin="72" bottom-margin="72" paging-mode="{paging}" fixrows="{fixrows}" '
        'width="595" height="842" orientation="portrait" html-report-align="left" '
        'bg-image="" html-interval-refresh-value="0" '
        f'column-enabled="{str(column_enabled).lower()}" '
        'api-page-param="page" api-page-size-param="page_size" '
        'api-total-count-path="data.total_count" api-page-size="50" '
        'api-start-field="start" api-end-field="end"'
        f'{extras}{column}></paper>'
    )


def report_xml(slug, title, fields, cells, row_count, widths, heights=None,
               column_enabled=False, column_count=2, paging="fitpage", fixrows=0,
               api_paging=False, api_summary=False, bands=None):
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<easyreport>'
        f'{datasource_xml(slug, fields)}'
        f'{"".join(cells)}'
        f'{rows_xml(row_count, heights, bands)}'
        f'{columns_xml(widths)}'
        f'{paper_xml(column_enabled, column_count, paging, fixrows, api_paging, api_summary)}'
        '</easyreport>\n'
    )


def write_data(slug, title, rows):
    payload = {
        "code": 200,
        "message": "success",
        "data": {
            "demo": title,
            "total_count": max(len(rows), 1),
            "rows": rows,
        },
    }
    path = REPORTDATA_DIR / f"{slug}.html"
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def field_order(rows):
    keys = []
    for row in rows:
        for key in row.keys():
            if key not in keys:
                keys.append(key)
    return keys


def title_cells(title, width):
    return [
        simple_cell(
            1, 1, title, col_span=width,
            style_xml=style(font_size=16, bold=True, bgcolor="64,158,255", forecolor="255,255,255"),
        ),
        simple_cell(
            2, 1, f"数据源：{DATA_BASE_URL}", col_span=width,
            style_xml=style(font_size=9, align="left", bgcolor="245,247,250", with_border=False),
        ),
    ]


def table_report(demo, fields=None, numeric_fields=None, column_enabled=False,
                 paging="fitpage", fixrows=0, api_paging=False, wrap_fields=None):
    rows = demo["rows"]
    fields = fields or field_order(rows)
    numeric_fields = numeric_fields or []
    wrap_fields = wrap_fields or []
    widths = demo.get("widths") or [68 if f in ("序号", "排名") else 110 for f in fields]
    cells = title_cells(demo["title"], len(fields))
    header_style = style(font_size=10, bold=True, bgcolor="230,235,245")
    for col, field in enumerate(fields, start=1):
        cells.append(simple_cell(3, col, field, style_xml=header_style))
    data_style = style(font_size=10)
    for col, field in enumerate(fields, start=1):
        fmt = "#,##0.00" if field in numeric_fields else None
        wrap = field in wrap_fields
        cell_style = style(font_size=10, fmt=fmt, wrap=wrap)
        left = "A4" if col > 1 else None
        expand = "Down" if col == 1 else "None"
        cells.append(dataset_cell(4, col, "ds", field, expand=expand, left_cell=left, style_xml=cell_style))
    if numeric_fields:
        total_row = 5
        first_numeric_col = min(fields.index(field) + 1 for field in numeric_fields if field in fields)
        label_span = max(first_numeric_col - 1, 1)
        cells.append(simple_cell(total_row, 1, "合计", col_span=label_span,
                                 style_xml=style(font_size=10, bold=True, bgcolor="240,249,235")))
        for col, field in enumerate(fields, start=1):
            if field in numeric_fields:
                cells.append(expr_cell(total_row, col, f"sum({col_name(col)}4)",
                                       left_cell="A5", top_cell="root",
                                       style_xml=style(font_size=10, bold=True, bgcolor="240,249,235", fmt="#,##0.00")))
        row_count = 5
    else:
        row_count = 4
    heights = {1: 32, 2: 20, 3: 24, 4: 22, 5: 24}
    return report_xml(
        demo["slug"], demo["title"], fields, cells, row_count, widths, heights,
        column_enabled=column_enabled, column_count=demo.get("column_count", 2),
        paging=paging, fixrows=fixrows, api_paging=api_paging,
    )


def double_header_report(demo):
    fields = field_order(demo["rows"])
    widths = [90, 120, 110, 95, 110, 95, 90]
    cells = title_cells(demo["title"], 7)
    header_style = style(font_size=10, bold=True, bgcolor="230,235,245")
    cells.extend([
        simple_cell(3, 1, "区域", row_span=2, style_xml=header_style),
        simple_cell(3, 2, "项目", row_span=2, style_xml=header_style),
        simple_cell(3, 3, "本月", col_span=2, style_xml=header_style),
        simple_cell(3, 5, "累计", col_span=2, style_xml=header_style),
        simple_cell(3, 7, "完成率", row_span=2, style_xml=header_style),
        simple_cell(4, 3, "销售额", style_xml=header_style),
        simple_cell(4, 4, "订单数", style_xml=header_style),
        simple_cell(4, 5, "销售额", style_xml=header_style),
        simple_cell(4, 6, "订单数", style_xml=header_style),
    ])
    for col, field in enumerate(fields, start=1):
        fmt = "##.##%" if field == "完成率" else ("#,##0.00" if "销售额" in field else None)
        cells.append(dataset_cell(
            5, col, "ds", field, expand="Down" if col == 1 else "None",
            left_cell="A5" if col > 1 else None, style_xml=style(font_size=10, fmt=fmt),
        ))
    return report_xml(demo["slug"], demo["title"], fields, cells, 5, widths,
                      {1: 32, 2: 20, 3: 24, 4: 24, 5: 22})


def group_report(demo, levels=1):
    fields = field_order(demo["rows"])
    widths = [90, 120, 120, 95, 80, 90]
    cells = title_cells(demo["title"], len(fields))
    header_style = style(font_size=10, bold=True, bgcolor="230,235,245")
    for col, field in enumerate(fields, start=1):
        cells.append(simple_cell(3, col, field, style_xml=header_style))
    if levels == 1:
        group_fields = {"区域"}
        row_span = None
    else:
        group_fields = {"区域", "项目"}
        row_span = {1: 2, 2: 1}
    for col, field in enumerate(fields, start=1):
        aggregate = "group" if field in group_fields else "select"
        order = "asc" if field in group_fields else "none"
        span = row_span.get(col) if isinstance(row_span, dict) else None
        if field in group_fields:
            expand = "Down"
            left_cell = f"{col_name(col - 1)}4" if col > 1 else None
        else:
            detail_driver_col = len(group_fields) + 1
            expand = "Down" if levels == 1 or col == detail_driver_col else "None"
            left_cell = f"{col_name(col - 1)}4" if col == detail_driver_col else f"{col_name(detail_driver_col)}4"
        cells.append(dataset_cell(
            4, col, "ds", field, aggregate=aggregate,
            expand=expand,
            left_cell=left_cell,
            row_span=span,
            order=order,
            style_xml=style(font_size=10, fmt="#,##0.00" if field == "金额" else None),
        ))
    cells.append(simple_cell(5, 1, "小计", col_span=3, left_cell="A4", top_cell="root",
                             style_xml=style(font_size=10, bold=True, bgcolor="240,249,235")))
    amount_col = fields.index("金额") + 1
    count_col = fields.index("数量") + 1
    cells.append(expr_cell(5, amount_col, f"sum({col_name(amount_col)}4)", left_cell="A5", top_cell="root",
                           style_xml=style(font_size=10, bold=True, bgcolor="240,249,235", fmt="#,##0.00")))
    cells.append(expr_cell(5, count_col, f"sum({col_name(count_col)}4)", left_cell="A5", top_cell="root",
                           style_xml=style(font_size=10, bold=True, bgcolor="240,249,235")))
    return report_xml(demo["slug"], demo["title"], fields, cells, 5, widths,
                      {1: 32, 2: 20, 3: 24, 4: 22, 5: 24})


def dynamic_columns_report(demo, hide_optional=False):
    fields = field_order(demo["rows"])
    widths = [120, 90, 90, 80, 80, 80, 80, 80]
    cells = title_cells(demo["title"], len(fields))
    header_style = style(font_size=10, bold=True, bgcolor="230,235,245")
    for col, field in enumerate(fields, start=1):
        if col <= 3:
            cells.append(simple_cell(3, col, field, style_xml=header_style))
        else:
            extra = ""
            if hide_optional and field == "空列":
                extra = (
                    '<condition-property-item name="空列隐藏" col-width="0">'
                    f'{condition_style()}'
                    '<condition op="==" id="hidden-col" type="property"><value><![CDATA[null]]></value></condition>'
                    '</condition-property-item>'
                )
            cells.append(expr_cell(3, col, f"field_name('ds', {col})", style_xml=header_style, extra_xml=extra))
    for col, field in enumerate(fields, start=1):
        if col <= 3:
            cells.append(dataset_cell(4, col, "ds", field, expand="Down" if col == 1 else "None",
                                      left_cell="A4" if col > 1 else None, style_xml=style(font_size=10)))
        else:
            cells.append(dataset_cell(4, col, "ds", "", expand="None", left_cell="A4",
                                      use_index=True, index=col,
                                      style_xml=style(font_size=10, fmt="#,##0.00")))
    return report_xml(demo["slug"], demo["title"], fields, cells, 4, widths,
                      {1: 32, 2: 20, 3: 24, 4: 22})


def chart_report(demo, chart_type):
    fields = field_order(demo["rows"])
    widths = [95, 95, 95, 95, 95, 95]
    cells = title_cells(demo["title"], 6)
    chart_xml = (
        '<chart-value>'
        f'<dataset dataset-name="ds" type="{chart_type}" category-property="类别" '
        'series-type="property" series-property="系列" value-property="数值" collect-type="sum"/>'
        '<xaxes><scale-label display="true" label-string="类别"/></xaxes>'
        '<yaxes><scale-label display="true" label-string="数值"/></yaxes>'
        f'<option type="title" display="true" position="top" text="{attr(demo["title"])}"/>'
        '<option type="legend" display="true" position="bottom"/>'
        '<option type="animation" duration="600" easing="easeOutQuad"/>'
        '<plugin name="data-labels" display="false"/>'
        '</chart-value>'
    )
    cells.append(
        f'<cell {cell_attrs("A3", 3, 1, "None", col_span=6, row_span=10)}>'
        f'{chart_xml}{style(font_size=10, with_border=True)}</cell>'
    )
    return report_xml(demo["slug"], demo["title"], fields, cells, 12, widths,
                      {1: 32, 2: 20, **{i: 24 for i in range(3, 13)}})


def mail_label_report(demo):
    fields = field_order(demo["rows"])
    widths = [120, 150, 230, 120]
    cells = title_cells(demo["title"], 4)
    label_style = style(font_size=10, bold=True, bgcolor="230,235,245")
    for col, field in enumerate(fields, start=1):
        cells.append(simple_cell(3, col, field, style_xml=label_style))
    for col, field in enumerate(fields, start=1):
        wrap = field == "地址"
        cells.append(dataset_cell(4, col, "ds", field, expand="Down" if col == 1 else "None",
                                  left_cell="A4" if col > 1 else None,
                                  style_xml=style(font_size=10, wrap=wrap, align="left" if wrap else "center")))
    return report_xml(demo["slug"], demo["title"], fields, cells, 4, widths,
                      {1: 32, 2: 20, 3: 24, 4: 38}, column_enabled=True, column_count=2)


def payment_receipt_report(demo):
    fields = field_order(demo["rows"])
    label_style = style(font_size=10, bold=True, bgcolor="253,245,245")
    value_style = style(font_size=11, align="left")
    cells = [
        simple_cell(
            1, 1, demo["title"], col_span=8,
            style_xml=style(
                font_size=20, bold=True, forecolor="192,57,43", with_border=False,
            ),
        ),
        simple_cell(2, 1, "收款单位", col_span=2, style_xml=label_style),
        dataset_cell(2, 3, "ds", "收款单位", col_span=3, style_xml=value_style),
        simple_cell(2, 6, "票据编号", col_span=2, style_xml=label_style),
        dataset_cell(2, 8, "ds", "票据编号", style_xml=value_style),
        simple_cell(3, 1, "付款单位", col_span=2, style_xml=label_style),
        dataset_cell(3, 3, "ds", "付款单位", col_span=6, style_xml=value_style),
        simple_cell(4, 1, "收款事由", col_span=2, style_xml=label_style),
        dataset_cell(
            4, 3, "ds", "收款事由", col_span=6,
            style_xml=style(font_size=11, align="left", wrap=True),
        ),
        simple_cell(5, 1, "人民币（大写）", col_span=2, style_xml=label_style),
        dataset_cell(5, 3, "ds", "人民币大写", col_span=4, style_xml=value_style),
        simple_cell(5, 7, "金额", style_xml=label_style),
        dataset_cell(
            5, 8, "ds", "金额",
            style_xml=style(font_size=11, bold=True, align="right", fmt="￥#,##0.00"),
        ),
        simple_cell(6, 1, "收款方式", col_span=2, style_xml=label_style),
        dataset_cell(6, 3, "ds", "收款方式", col_span=2, style_xml=value_style),
        simple_cell(6, 5, "收款日期", col_span=2, style_xml=label_style),
        dataset_cell(6, 7, "ds", "收款日期", col_span=2, style_xml=value_style),
        simple_cell(7, 1, "备注", col_span=2, style_xml=label_style),
        dataset_cell(
            7, 3, "ds", "备注", col_span=6,
            style_xml=style(font_size=10, align="left", wrap=True),
        ),
        simple_cell(8, 1, "收款人", col_span=2, style_xml=label_style),
        dataset_cell(8, 3, "ds", "收款人", style_xml=value_style),
        simple_cell(8, 4, "经办人", col_span=2, style_xml=label_style),
        dataset_cell(8, 6, "ds", "经办人", style_xml=value_style),
        simple_cell(8, 7, "复核人", style_xml=label_style),
        dataset_cell(8, 8, "ds", "复核人", style_xml=value_style),
        simple_cell(
            9, 1, "本收据一式两联，付款单位与收款单位各执一联。", col_span=8,
            style_xml=style(font_size=9, forecolor="96,98,102", align="left", with_border=False),
        ),
    ]
    paper = (
        '<paper type="A4" left-margin="54" right-margin="54" top-margin="54" '
        'bottom-margin="54" paging-mode="fitpage" fixrows="0" width="842" height="595" '
        'orientation="landscape" html-report-align="center" bg-image="" '
        'html-interval-refresh-value="0" column-enabled="false" '
        'api-page-param="page" api-page-size-param="page_size" '
        'api-total-count-path="data.total_count" api-page-size="1" '
        'api-start-field="start" api-end-field="end"></paper>'
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<easyreport>'
        f'{datasource_xml(demo["slug"], fields)}'
        f'{"".join(cells)}'
        f'{rows_xml(9, {1: 44, 2: 30, 3: 34, 4: 50, 5: 36, 6: 32, 7: 42, 8: 32, 9: 24})}'
        f'{columns_xml([72, 72, 112, 82, 82, 82, 72, 130])}'
        f'{paper}'
        '</easyreport>\n'
    )


def build_rows():
    business_rows = [
        {"序号": 1, "区域": "华东", "项目": "上海中心", "月份": "2026-01", "指标": "合同收入", "金额": 128650.50, "同比": 0.126, "状态": "正常"},
        {"序号": 2, "区域": "华东", "项目": "杭州未来城", "月份": "2026-01", "指标": "物业费", "金额": 86420.00, "同比": 0.084, "状态": "正常"},
        {"序号": 3, "区域": "华南", "项目": "深圳湾", "月份": "2026-01", "指标": "停车收入", "金额": 54210.30, "同比": -0.023, "状态": "关注"},
        {"序号": 4, "区域": "华北", "项目": "北京金融街", "月份": "2026-01", "指标": "能源费", "金额": 73450.00, "同比": 0.051, "状态": "正常"},
        {"序号": 5, "区域": "西南", "项目": "成都天府", "月份": "2026-01", "指标": "多种经营", "金额": 39780.90, "同比": 0.173, "状态": "正常"},
        {"序号": 6, "区域": "华中", "项目": "武汉光谷", "月份": "2026-01", "指标": "广告位", "金额": 22800.00, "同比": 0.041, "状态": "正常"},
    ]
    group_rows = [
        {"区域": "华东", "项目": "上海中心", "科目": "物业费", "金额": 286000.00, "数量": 184, "完成率": 0.93},
        {"区域": "华东", "项目": "上海中心", "科目": "停车费", "金额": 118000.00, "数量": 96, "完成率": 0.88},
        {"区域": "华东", "项目": "杭州未来城", "科目": "物业费", "金额": 176000.00, "数量": 126, "完成率": 0.91},
        {"区域": "华南", "项目": "深圳湾", "科目": "物业费", "金额": 238000.00, "数量": 142, "完成率": 0.89},
        {"区域": "华南", "项目": "深圳湾", "科目": "能源费", "金额": 86000.00, "数量": 73, "完成率": 0.84},
        {"区域": "华北", "项目": "北京金融街", "科目": "物业费", "金额": 256000.00, "数量": 151, "完成率": 0.95},
    ]
    sales_rows = [
        {"区域": "华东", "项目": "上海中心", "本月销售额": 326000.00, "本月订单数": 156, "累计销售额": 1386000.00, "累计订单数": 638, "完成率": 0.92},
        {"区域": "华东", "项目": "杭州未来城", "本月销售额": 218000.00, "本月订单数": 102, "累计销售额": 986000.00, "累计订单数": 481, "完成率": 0.88},
        {"区域": "华南", "项目": "深圳湾", "本月销售额": 278000.00, "本月订单数": 126, "累计销售额": 1168000.00, "累计订单数": 536, "完成率": 0.90},
        {"区域": "华北", "项目": "北京金融街", "本月销售额": 305000.00, "本月订单数": 141, "累计销售额": 1295000.00, "累计订单数": 602, "完成率": 0.94},
    ]
    label_rows = [
        {"姓名": "陈明", "公司": "上海中心运营部", "地址": "上海市浦东新区陆家嘴环路 1000 号 18 层", "电话": "138-0000-1001"},
        {"姓名": "李佳", "公司": "杭州未来城管理处", "地址": "杭州市余杭区文一西路 998 号 A 座", "电话": "138-0000-1002"},
        {"姓名": "周宁", "公司": "深圳湾客户中心", "地址": "深圳市南山区海德三道 12 号", "电话": "138-0000-1003"},
        {"姓名": "王越", "公司": "北京金融街服务中心", "地址": "北京市西城区金融大街 88 号", "电话": "138-0000-1004"},
    ]
    receipt_rows = [
        {
            "收款单位": "上海易报信息科技有限公司",
            "票据编号": "SK202607180001",
            "付款单位": "杭州未来城商业管理有限公司",
            "收款事由": "收到 2026 年 7 月企业报表平台技术服务费",
            "人民币大写": "人民币壹万贰仟捌佰元整",
            "金额": 12800.00,
            "收款方式": "银行转账",
            "收款日期": "2026-07-18",
            "备注": "合同编号：ER-2026-0718；本票据仅作为收款凭证。",
            "收款人": "陈明",
            "经办人": "李佳",
            "复核人": "周宁",
        },
    ]
    dynamic_rows = [
        {"项目": "上海中心", "类型": "收入", "负责人": "陈明", "2026-01": 128650.50, "2026-02": 135200.00, "2026-03": 142900.00, "2026-04": 139600.00, "2026-05": 151000.00},
        {"项目": "杭州未来城", "类型": "收入", "负责人": "李佳", "2026-01": 86420.00, "2026-02": 90210.00, "2026-03": 93600.00, "2026-04": 97000.00, "2026-05": 101300.00},
        {"项目": "深圳湾", "类型": "收入", "负责人": "周宁", "2026-01": 54210.30, "2026-02": 58300.00, "2026-03": 62100.00, "2026-04": 64800.00, "2026-05": 67200.00},
    ]
    hidden_rows = [
        {"项目": "上海中心", "类型": "导出", "负责人": "陈明", "空列": None, "一月": 128650.50, "二月": 135200.00, "三月": 142900.00, "四月": 139600.00},
        {"项目": "杭州未来城", "类型": "导出", "负责人": "李佳", "空列": None, "一月": 86420.00, "二月": 90210.00, "三月": 93600.00, "四月": 97000.00},
    ]
    chart_rows = [
        {"类别": "1月", "系列": "收入", "数值": 326000.00},
        {"类别": "2月", "系列": "收入", "数值": 352000.00},
        {"类别": "3月", "系列": "收入", "数值": 389000.00},
        {"类别": "4月", "系列": "收入", "数值": 375000.00},
        {"类别": "1月", "系列": "成本", "数值": 186000.00},
        {"类别": "2月", "系列": "成本", "数值": 198000.00},
        {"类别": "3月", "系列": "成本", "数值": 205000.00},
        {"类别": "4月", "系列": "成本", "数值": 201000.00},
    ]
    api_rows = [
        {"序号": 1, "接口": "/reportdata/simple-table.html", "方法": "GET", "数据路径": "data.rows", "耗时ms": 18, "状态": "200"},
        {"序号": 2, "接口": "/reportdata/group-summary.html", "方法": "GET", "数据路径": "data.rows", "耗时ms": 21, "状态": "200"},
        {"序号": 3, "接口": "/reportdata/chart-bar.html", "方法": "GET", "数据路径": "data.rows", "耗时ms": 24, "状态": "200"},
    ]
    export_rows = [
        {"序号": 1, "项目": "上海中心", "业务日期": "2026-01-05", "金额": 128650.50, "导出模式": "标准导出", "状态": "已完成"},
        {"序号": 2, "项目": "杭州未来城", "业务日期": "2026-01-06", "金额": 86420.00, "导出模式": "流式导出", "状态": "处理中"},
        {"序号": 3, "项目": "深圳湾", "业务日期": "2026-01-07", "金额": 54210.30, "导出模式": "分页分Sheet", "状态": "已完成"},
        {"序号": 4, "项目": "北京金融街", "业务日期": "2026-01-08", "金额": 73450.00, "导出模式": "PDF归档", "状态": "已完成"},
    ]
    long_param_rows = [
        {"参数名": "url", "参数值": "http://localhost/reportdata/param-text-wrap.html?start=2026-01-01&end=2026-12-31&page=1&page_size=50", "说明": "长 URL 自动换行展示"},
        {"参数名": "requestBody", "参数值": '{"token":"${token}","params":{"start":"${start}","end":"${end}","page":"${page}","page_size":"${page_size}"}}', "说明": "请求体参数模板"},
    ]
    return business_rows, group_rows, sales_rows, label_rows, receipt_rows, dynamic_rows, hidden_rows, chart_rows, api_rows, export_rows, long_param_rows


def build_demos():
    business, group, sales, labels, receipts, dynamic, hidden, chart, api, export, long_params = build_rows()
    demos = [
        {"slug": "simple-table", "title": "简单表格报表", "kind": "table", "rows": business,
         "numeric": ["金额"], "widths": [60, 85, 120, 90, 100, 95, 80, 75]},
        {"slug": "double-header", "title": "双层表头报表", "kind": "double", "rows": sales},
        {"slug": "fixed-column", "title": "锁定列报表", "kind": "table", "rows": business,
         "numeric": ["金额"], "widths": [60, 100, 140, 95, 110, 100, 85, 80]},
        {"slug": "multi-column", "title": "多栏报表", "kind": "table", "rows": business[:4],
         "numeric": ["金额"], "column_enabled": True, "column_count": 2, "widths": [50, 80, 100, 80, 90, 85, 70, 65]},
        {"slug": "zebra-detail", "title": "交替色明细报表", "kind": "table", "rows": business,
         "numeric": ["金额"], "widths": [60, 85, 120, 90, 100, 95, 80, 75]},
        {"slug": "mail-label", "title": "邮件标签报表", "kind": "label", "rows": labels},
        {"slug": "payment-receipt", "title": "收款票据", "kind": "receipt", "rows": receipts},
        {"slug": "simple-group", "title": "简单分组报表", "kind": "group1", "rows": group},
        {"slug": "multi-level-group", "title": "多层分组报表", "kind": "group2", "rows": group},
        {"slug": "group-summary", "title": "分组统计报表", "kind": "group2", "rows": group},
        {"slug": "crosstab", "title": "交叉表报表", "kind": "dynamic", "rows": dynamic},
        {"slug": "dynamic-columns", "title": "动态列报表", "kind": "dynamic", "rows": dynamic},
        {"slug": "split-paper", "title": "切分纸张报表", "kind": "table", "rows": business[:4],
         "numeric": ["金额"], "column_enabled": True, "column_count": 2, "widths": [50, 80, 100, 80, 90, 85, 70, 65]},
        {"slug": "chart-bar", "title": "柱状图报表", "kind": "chart", "chart_type": "bar", "rows": chart},
        {"slug": "chart-line", "title": "折线图报表", "kind": "chart", "chart_type": "line", "rows": chart},
        {"slug": "chart-pie", "title": "饼图报表", "kind": "chart", "chart_type": "pie", "rows": chart[:4]},
        {"slug": "chart-radar", "title": "雷达图报表", "kind": "chart", "chart_type": "radar", "rows": chart},
        {"slug": "jdbc-database", "title": "JDBC 数据库报表", "kind": "table", "rows": [
            {"序号": 1, "数据库": "MySQL", "表名": "er_order", "行数": 1286, "同步方式": "HTTP模拟", "状态": "正常"},
            {"序号": 2, "数据库": "PostgreSQL", "表名": "er_invoice", "行数": 936, "同步方式": "HTTP模拟", "状态": "正常"},
            {"序号": 3, "数据库": "SQL Server", "表名": "er_contract", "行数": 562, "同步方式": "HTTP模拟", "状态": "正常"},
        ], "numeric": ["行数"], "widths": [60, 120, 130, 90, 110, 80]},
        {"slug": "http-api", "title": "HTTP API 数据源报表", "kind": "table", "rows": api, "numeric": ["耗时ms"],
         "widths": [60, 240, 70, 110, 80, 70]},
        {"slug": "excel-export", "title": "Excel 导出报表", "kind": "table", "rows": export, "numeric": ["金额"],
         "api_paging": True, "widths": [60, 130, 100, 95, 110, 80]},
        {"slug": "pdf-export", "title": "PDF 导出报表", "kind": "table", "rows": export, "numeric": ["金额"],
         "widths": [60, 130, 100, 95, 110, 80]},
        {"slug": "sse-export-progress", "title": "SSE 实时进度导出", "kind": "table", "rows": export, "numeric": ["金额"],
         "api_paging": True, "paging": "fixrows", "fixrows": 20, "widths": [60, 130, 100, 95, 110, 80]},
        {"slug": "html-preview", "title": "HTML 在线预览报表", "kind": "table", "rows": business,
         "numeric": ["金额"], "widths": [60, 85, 120, 90, 100, 95, 80, 75]},
        {"slug": "dynamic-index-binding", "title": "动态列按序号绑定", "kind": "dynamic", "rows": dynamic},
        {"slug": "http-rest-api", "title": "HTTP/REST API 数据源", "kind": "table", "rows": api, "numeric": ["耗时ms"],
         "widths": [60, 240, 70, 110, 80, 70]},
        {"slug": "api-paging-export", "title": "分页导出配置", "kind": "table", "rows": export, "numeric": ["金额"],
         "api_paging": True, "paging": "fixrows", "fixrows": 20, "widths": [60, 130, 100, 95, 110, 80]},
        {"slug": "multi-export-mode", "title": "多导出模式", "kind": "table", "rows": export, "numeric": ["金额"],
         "api_paging": True, "widths": [60, 130, 100, 95, 110, 80]},
        {"slug": "hidden-column-width", "title": "隐藏列宽度修正", "kind": "dynamic-hidden", "rows": hidden},
        {"slug": "param-text-wrap", "title": "参数表格优化", "kind": "table", "rows": long_params,
         "wrap_fields": ["参数值"], "widths": [110, 420, 160]},
    ]
    return demos


def render_report(demo):
    kind = demo["kind"]
    if kind == "double":
        return double_header_report(demo)
    if kind == "label":
        return mail_label_report(demo)
    if kind == "receipt":
        return payment_receipt_report(demo)
    if kind == "group1":
        return group_report(demo, levels=1)
    if kind == "group2":
        return group_report(demo, levels=2)
    if kind == "dynamic":
        return dynamic_columns_report(demo)
    if kind == "dynamic-hidden":
        return dynamic_columns_report(demo, hide_optional=True)
    if kind == "chart":
        return chart_report(demo, demo["chart_type"])
    return table_report(
        demo,
        numeric_fields=demo.get("numeric", []),
        column_enabled=demo.get("column_enabled", False),
        paging=demo.get("paging", "fitpage"),
        fixrows=demo.get("fixrows", 0),
        api_paging=demo.get("api_paging", False),
        wrap_fields=demo.get("wrap_fields", []),
    )


def update_preview_links(demos):
    html = PREVIEW_HTML.read_text(encoding="utf-8")
    for demo in demos:
        title = re.escape(demo["title"])
        report_file = f'{demo["slug"]}.easyreport.xml'
        preview_url = f'{REPORT_BASE_URL}/preview?_u=file:{report_file}'
        design_url = f'{REPORT_BASE_URL}/designer?_u=file:{report_file}'
        replacement = (
            r'\1'
            f'<a href="{preview_url}" target="_blank" rel="noopener" class="pc-link">预览</a>\n'
            f'          <a href="{design_url}" target="_blank" rel="noopener" class="pc-link">设计</a>'
        )
        patterns = [
            re.compile(
                rf'(<span class="pt-title">{title}</span>.*?<div class="pt-actions">\s*)'
                r'<a [^>]*>预览</a>\s*<a [^>]*>设计</a>',
                re.S,
            ),
            re.compile(
                rf'(<h4>{title}</h4>.*?<div class="pc-actions">\s*)'
                r'<a [^>]*>预览</a>\s*<a [^>]*>设计</a>',
                re.S,
            ),
        ]
        count = 0
        for pattern in patterns:
            html, count = pattern.subn(replacement, html, count=1)
            if count == 1:
                break
        if count != 1:
            raise RuntimeError(f"preview.html 中没有找到演示卡片：{demo['title']}")
    PREVIEW_HTML.write_text(html, encoding="utf-8")


def main():
    REPORTDATA_DIR.mkdir(parents=True, exist_ok=True)
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    demos = build_demos()
    index = []
    for demo in demos:
        write_data(demo["slug"], demo["title"], demo["rows"])
        report = render_report(demo)
        report_path = REPORT_DIR / f'{demo["slug"]}.easyreport.xml'
        report_path.write_text(report, encoding="utf-8")
        index.append({
            "title": demo["title"],
            "slug": demo["slug"],
            "dataUrl": f'{DATA_BASE_URL}/{demo["slug"]}.html',
            "reportFile": f'{demo["slug"]}.easyreport.xml',
            "previewUrl": f'{REPORT_BASE_URL}/preview?_u=file:{demo["slug"]}.easyreport.xml',
            "designerUrl": f'{REPORT_BASE_URL}/designer?_u=file:{demo["slug"]}.easyreport.xml',
        })
    INDEX_FILE.write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    update_preview_links(demos)
    print(f"generated {len(demos)} report demos")


if __name__ == "__main__":
    main()
