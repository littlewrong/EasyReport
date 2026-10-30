# 单元格 HTML 富文本

选中普通文本或表达式单元格，在右侧属性面板勾选“按 HTML 渲染”，保存后打开网页预览。
支持批量设置选区内的普通文本、表达式单元格；旧报表默认关闭。
设计器继续显示源内容，表达式仍按原有规则计算，结果的格式在预览中查看。

普通文本内容示例：

```html
<p><span style="color:red;font-size:16pt;font-weight:bold">重要说明</span></p>
<p>支持<strong>加粗</strong>、<em>斜体</em>、<u>下划线</u>和<br>换行。</p>
<ul><li>第一项</li><li>第二项</li></ul>
```

表达式应返回 HTML 字符串，例如 `"<strong>计算结果</strong>"`，也可以返回数据源中的 HTML 字段。
`&lt;b&gt;` 等已编码的标签仍按文字显示，不会自动反复解码。

## 支持范围

- 标签：`b/strong`、`i/em`、`u`、`s/strike/del`、`sub/sup`、`span`、`br`、`p/div`、`ul/ol/li`、`h1` 至 `h6`、`blockquote`、`font`、`a`。
- 内联样式：字体、字号、字重、斜体、文字颜色、背景颜色、文字装饰、水平/垂直对齐、行高、首行缩进和段落边距。
- 兼容段落的 `align`，`font` 的 `color/size/face`，以及有序列表的 `start`。
- 链接仅保留允许的地址协议；已设置单元格超链接时，以单元格链接为准，内部链接转为文字，避免链接嵌套。

HTML 经服务端白名单清理后输出。脚本、事件属性、外部样式、图片、表格、嵌入页面及定位等复杂布局不在本阶段支持范围内。
仅依赖外部 CSS 类名的格式不会保留，请使用上述内联样式。

## 换行、打印与导出

HTML 模式自动估算内容高度，并按单元格宽度换行，不再使用普通文本的“换行计算”去插入字符。
预览和普通“在线打印”共用 HTML 输出与富文本基础样式。
服务端使用 Java 8 的文本排版估算高度，与浏览器字体渲染可能有小幅差异；复杂内容、跨页合并及单个内容超过一页时，应检查打印预览并酌情增加模板行高。

PDF 打印及 PDF、Word（DOCX）导出也使用这个开关，正文输出为原生文字，支持复制、搜索和 Word 编辑。Excel 不做 HTML 富文本转换。

单栏报表中只要存在可见 HTML 单元格，PDF、Word 会按实际文字内容连续排版，允许长合同正文跨页，不再按网页预览的分页副本重复整份正文。PDF 仍使用配置的纸张尺寸、页边距以及页眉页脚；Word 使用原生可跨页表格和段落。分页位置由各自的排版引擎决定，可能与网页预览不同。

合同正文可以使用以下样式：

```html
<h1 style="font-size:18pt;text-align:center">合同标题</h1>
<p style="text-indent:2em;line-height:1.5;margin-bottom:6pt;text-align:justify">
    这里是合同正文，支持<strong>加粗条款</strong>和<u>签字下划线</u>。
</p>
```

首版主要验证单栏文字合同。报纸式多栏报表保留原来的报表分页，HTML 图片、嵌套表格和复杂布局仍不在本阶段支持范围内。重复表尾及包含页码函数的正文单元格也需要结合实际模板验证；建议页码放在 PDF 页眉页脚中。

### PDF 和 Word 行高

- 在 `p/div/li/blockquote` 上通过内联 `line-height` 分别设置段落行高；未设置的普通子段落继承父级设置。
- 无单位数字（如 `1.5`、`2`）表示倍数，`px/pt/em/%` 等带单位的值计算为固定高度。固定高度继承后不会因子标签的字号变化而被再次放大。
- `line-height:normal` 恢复默认行高，当前以 `1.2` 倍近似处理。标题 `h1` 至 `h6` 默认使用 `1.2`，可以在标题自身覆盖。
- 支持整段文字都使用相同行高的 `span` 写法，包括内部加粗、换行，以及多个行高一致的文字片段；不会因此缩小段落自身已设置的行高。只有部分文字设置行高，或同段文字设置不同的行高时，仍按段落行高导出。
- Word 的倍数行距支持小于 `1` 的正数；固定高度使用原生固定行距。过小的固定行高可能让大字号文字重叠或被 Word 裁切，应根据合同字号选择足够的高度。
- `line-height` 控制段内行距，段落之间的空白请使用 `margin-top/margin-bottom`。PDF、Word 与网页的字体度量和分页仍可能有差异。

```html
<div style="font-size:12pt;line-height:1.5">
    <p>这一段继承 1.5 倍行距。<br>换行后仍然使用相同行距。</p>
    <p style="line-height:24px">这一段固定为 18pt 行高。</p>
    <p><span style="line-height:2">整段 span 的 2 倍行距也会应用。</span></p>
    <p style="line-height:normal">这一段恢复默认行高。</p>
</div>
```

XML 在单元格上保存 `html="true"`，省略时为普通文本显示：

```xml
<cell name="A1" row="1" col="1" html="true">
    <simple-value><![CDATA[<strong>重要说明</strong>]]></simple-value>
</cell>
```

## 部署依赖与普通报表兼容

HTML 功能需要运行时依赖 `owasp-java-html-sanitizer:20261011.1` 和 `jsoup:1.21.2`，
`easyreport-core/pom.xml` 已声明这两个依赖。`org/owasp/html/CssSchema` 缺失表示运行环境未能加载 HTML 清理库。
原实现生成所有报表的公共 CSS 时就初始化该库，因此未启用 HTML 的旧报表也会报 500。
清理器现已改为仅在处理 HTML 内容时初始化，普通报表不依赖它；HTML 内容仍必须经过清理。

本项目 `easyreport-web` 使用 `easyreport-web/lib` 中的 system 依赖，源码构建后需要按下节流程刷新本地模块 JAR；
仅运行父项目 `mvn clean package -f ./pom.xml '-Dmaven.test.skip=true'` 不会自动更新这些本地 JAR。
web 的 POM 已显式声明 OWASP 和 Jsoup，部署生成的 `easyreport-web/target/easyreport-web-3.0.0.jar` 时会包含它们。
如果报表嵌入其他业务应用，需要同时更新 core 的 JAR 和 POM，再重新构建业务应用；
检查构建仓库是否缓存了不含新依赖的旧 `2.0.6` POM。手工维护运行库时，需一并加入
`owasp-java-html-sanitizer-20261011.1.jar` 和 `jsoup-1.21.2.jar`。
补齐运行库并部署修复后需重启应用，清除 JVM 已记录的类初始化失败状态。

## 验证

- 前端：在 `easyreport-js` 目录运行 `npm test`。
- 后端：在父项目目录运行 `mvn -pl easyreport-core -am -Djava.awt.headless=true test`。
- 前端资源：在 `easyreport-js` 目录运行 `node node_modules/webpack/bin/webpack.js --config webpack.config.js`。
## 调试与发布模式

本项目保留 `easyreport-test` 源码调试模式和 `easyreport-web` 本地 JAR 发布模式。
当前两个应用的版本为 `3.0.0`，core、console、font 本地模块的版本仍为 `2.0.6`。
更新功能源码后，先构建调试应用及其依赖，再将本项目新构建的 core、console、font 包复制到 `easyreport-web/lib`，最后构建发布应用，避免发布包仍使用旧的本地模块：

```powershell
mvn -pl easyreport-test -am '-DskipTests' package
foreach ($module in @('core', 'console', 'font')) {
    Copy-Item -LiteralPath "easyreport-$module/target/easyreport-$module-2.0.6.jar" -Destination "easyreport-web/lib/easyreport-$module-2.0.6.jar" -Force
}
mvn -pl easyreport-web '-DskipTests' package
```

web 的 system 依赖不传递第三方依赖，因此 OWASP HTML 清理库和 Jsoup 已在 web 的 POM 中显式配置；web/test 应用包也启用了重新生成 JAR，确保增量打包带入最新模块。
