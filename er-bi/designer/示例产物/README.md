# GoView 大屏示例产物

这些 JSON 文件可以在设计器工作台通过“导入”按钮导入。建议先选择“覆盖”查看整屏效果。

| 文件 | 主题 |
| --- | --- |
| `01-business-command-center.json` | 集团经营指挥中心 |
| `02-it-ops-observability.json` | 云平台运维态势大屏 |
| `03-energy-carbon-dashboard.json` | 能源碳排监测中心 |
| `04-logistics-dispatch-screen.json` | 智慧物流调度大屏 |
| `05-smart-park-security.json` | 智慧园区安防运营中心 |
| `06-retail-sales-war-room.json` | 新零售销售作战室 |
| `07-manufacturing-equipment.json` | 智能制造设备监控中心 |
| `08-emergency-response.json` | 城市应急响应指挥屏 |
| `09-member-growth-dashboard.json` | 会员增长分析大屏 |
| `10-finance-risk-control.json` | 财务风控经营看板 |

说明：

- 每个文件都是完整大屏产物，顶层结构为 `editCanvasConfig/requestGlobalConfig/componentList`。
- 每张大屏包含多个现有组件：标题、指标数字、边框面板、ECharts 图表、排名列表、轮播列表等。
- 02-10 已按不同场景调整版式、主视觉区域和组件组合，不只是替换标题和数据。
- 示例均使用静态数据，不依赖后端业务接口。
- 导入后可以继续在工作台拖拽、编辑样式、保存和发布。
