package cn.easyreport.export.richtext;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import cn.easyreport.definition.CellStyle;
import cn.easyreport.export.html.HtmlCellContent;
import cn.easyreport.model.Cell;
import cn.easyreport.model.Report;

/** Resolved text and paragraph styles shared by the native PDF and DOCX writers. */
public final class RichTextDocument {
    private final TextStyle style;
    private final List<Paragraph> paragraphs;

    private RichTextDocument(TextStyle style,List<Paragraph> paragraphs) {
        this.style=style;
        this.paragraphs=Collections.unmodifiableList(paragraphs);
    }

    public TextStyle getStyle() { return style; }
    public List<Paragraph> getParagraphs() { return paragraphs; }

    public static boolean hasHtml(Report report) {
        for(List<Cell> cells:report.getCellsMap().values()){
            for(Cell cell:cells){
                if(!cell.isForPaging() && cell.isHtmlRender() && cell.getRow()!=null
                        && !cell.getRow().isHide() && cell.getRow().getRealHeight()>0
                        && cell.getColumn()!=null && !cell.getColumn().isHide() && cell.getColumn().getWidth()>0){
                    return true;
                }
            }
        }
        return false;
    }

    public static RichTextDocument from(Cell cell) {
        TextStyle base=baseStyle(cell);
        boolean outerLink=cell.getLinkUrl()!=null && !cell.getLinkUrl().trim().isEmpty();
        if(outerLink){ base.linkUrl=safeLink(cell.getLinkUrl()); }
        Object value=cell.getFormatData();
        String html=HtmlCellContent.sanitize(value==null ? "" : value.toString(),!outerLink);
        Builder builder=new Builder();
        builder.paragraphStyle=base;
        for(Node node:Jsoup.parseBodyFragment(html).body().childNodes()){
            builder.visit(node,base,0);
        }
        builder.flush();
        if(builder.paragraphs.isEmpty()){
            builder.paragraphs.add(new Paragraph(base));
        }
        return new RichTextDocument(base,builder.paragraphs);
    }

    public static final class Paragraph {
        private TextStyle style;
        private final List<Run> runs=new ArrayList<Run>();
        private Paragraph(TextStyle style) { this.style=style; }
        public TextStyle getStyle() { return style; }
        public List<Run> getRuns() { return Collections.unmodifiableList(runs); }
        public String getText() {
            StringBuilder text=new StringBuilder();
            for(Run run:runs){ text.append(run.text); }
            return text.toString();
        }
    }

    public static final class Run {
        private final String text;
        private final TextStyle style;
        private Run(String text,TextStyle style) { this.text=text; this.style=style; }
        public String getText() { return text; }
        public TextStyle getStyle() { return style; }
    }

    public static final class TextStyle {
        private String fontFamily="宋体",color="000000",backgroundColor,align="left",linkUrl;
        private float fontSize=12,lineHeight=1.2f,firstLineIndent,leftIndent,spaceBefore,spaceAfter;
        private boolean bold,italic,underline,strike;
        private int script;
        private TextStyle() { }
        private TextStyle(TextStyle other) {
            fontFamily=other.fontFamily; color=other.color; backgroundColor=other.backgroundColor;
            align=other.align; linkUrl=other.linkUrl; fontSize=other.fontSize; lineHeight=other.lineHeight;
            firstLineIndent=other.firstLineIndent; leftIndent=other.leftIndent;
            spaceBefore=other.spaceBefore; spaceAfter=other.spaceAfter;
            bold=other.bold; italic=other.italic; underline=other.underline; strike=other.strike; script=other.script;
        }
        public String getFontFamily() { return fontFamily; }
        public float getFontSize() { return fontSize; }
        public boolean isBold() { return bold; }
        public boolean isItalic() { return italic; }
        public boolean isUnderline() { return underline; }
        public boolean isStrike() { return strike; }
        public String getColor() { return color; }
        public String getBackgroundColor() { return backgroundColor; }
        public String getAlign() { return align; }
        public float getLineHeight() { return lineHeight; }
        public float getFirstLineIndent() { return firstLineIndent; }
        public float getLeftIndent() { return leftIndent; }
        public float getSpaceBefore() { return spaceBefore; }
        public float getSpaceAfter() { return spaceAfter; }
        public int getScript() { return script; }
        public String getLinkUrl() { return linkUrl; }
    }

    private static TextStyle baseStyle(Cell cell) {
        TextStyle style=new TextStyle();
        for(CellStyle source:new CellStyle[]{cell.getCellStyle(),cell.getCustomCellStyle(),
                cell.getRow()==null ? null : cell.getRow().getCustomCellStyle(),
                cell.getColumn()==null ? null : cell.getColumn().getCustomCellStyle()}){
            if(source==null){ continue; }
            if(source.getFontFamily()!=null && !source.getFontFamily().trim().isEmpty()){ style.fontFamily=source.getFontFamily(); }
            if(source.getFontSize()>0){ style.fontSize=source.getFontSize(); }
            if(source.getBold()!=null){ style.bold=source.getBold(); }
            if(source.getItalic()!=null){ style.italic=source.getItalic(); }
            if(source.getUnderline()!=null){ style.underline=source.getUnderline(); }
            if(source.getAlign()!=null){ style.align=source.getAlign().name(); }
            if(source.getLineHeight()>0 && Float.isFinite(source.getLineHeight())){ style.lineHeight=source.getLineHeight(); }
            if(source.getForecolor()!=null){ style.color=color(source.getForecolor(),style.color); }
            if(source.getBgcolor()!=null){ style.backgroundColor=color(source.getBgcolor(),style.backgroundColor); }
        }
        return style;
    }

    private static final class Builder {
        private final List<Paragraph> paragraphs=new ArrayList<Paragraph>();
        private Paragraph current;
        private String pendingPrefix;
        private TextStyle paragraphStyle;

        void visit(Node node,TextStyle inherited,int depth) {
            if(node instanceof TextNode){
                text(((TextNode)node).getWholeText().replaceAll("[\\t\\n\\r\\f ]+"," "),inherited);
                return;
            }
            if(!(node instanceof Element)){ return; }
            Element element=(Element)node;
            String tag=element.normalName();
            TextStyle style=elementStyle(element,inherited);
            if("br".equals(tag)){ add("\n",style); return; }
            if("ul".equals(tag) || "ol".equals(tag)){
                flush();
                int index=1;
                try { index=Integer.parseInt(element.attr("start")); } catch(NumberFormatException ignored) { }
                for(Node child:element.childNodes()){
                    if(child instanceof Element && ((Element)child).normalName().equals("li")){
                        TextStyle item=elementStyle((Element)child,style);
                        item.leftIndent=style.leftIndent+style.fontSize*2;
                        item.firstLineIndent=-style.fontSize;
                        TextStyle enclosing=paragraphStyle;
                        paragraphStyle=item;
                        pendingPrefix="ol".equals(tag) ? (index++)+". " : "• ";
                        for(Node part:child.childNodes()){ visit(part,item,depth+1); }
                        if(pendingPrefix!=null){ ensure(item); }
                        flush();
                        pendingPrefix=null;
                        paragraphStyle=enclosing;
                    }else if(!(child instanceof TextNode) || !((TextNode)child).isBlank()){
                        visit(child,style,depth+1);
                        flush();
                    }
                }
                return;
            }
            boolean block=isBlock(tag);
            TextStyle enclosing=paragraphStyle;
            int before=paragraphs.size();
            if(block){ flush(); before=paragraphs.size(); paragraphStyle=style; }
            for(Node child:element.childNodes()){ visit(child,style,depth); }
            if(block){
                flush();
                if(paragraphs.size()==before){ paragraphs.add(new Paragraph(style)); }
                Paragraph first=paragraphs.get(before),last=paragraphs.get(paragraphs.size()-1);
                if(first.style!=style){
                    TextStyle adjusted=new TextStyle(first.style);
                    adjusted.spaceBefore+=style.spaceBefore;
                    first.style=adjusted;
                }
                if(last.style!=style){
                    TextStyle adjusted=new TextStyle(last.style);
                    adjusted.spaceAfter+=style.spaceAfter;
                    last.style=adjusted;
                }
                paragraphStyle=enclosing;
            }
        }

        void text(String text,TextStyle style) {
            if(text.isEmpty()){ return; }
            if(current==null || current.getText().endsWith(" ") || current.getText().endsWith("\n")){
                if(text.startsWith(" ")){ text=text.substring(1); }
            }
            if(!text.isEmpty()){ add(text,style); }
        }
        void ensure(TextStyle style) {
            if(current==null){ current=new Paragraph(paragraphStyle==null ? style : paragraphStyle); }
            if(pendingPrefix!=null){
                current.runs.add(new Run(pendingPrefix,style));
                pendingPrefix=null;
            }
        }
        void add(String text,TextStyle style) {
            ensure(style);
            current.runs.add(new Run(text,style));
        }
        void flush() {
            if(current!=null){
                int last=current.runs.size()-1;
                if(last>=0){
                    Run run=current.runs.get(last);
                    String trimmed=run.text.replaceAll(" +$","");
                    current.runs.set(last,new Run(trimmed,run.style));
                }
                paragraphs.add(current);
                current=null;
            }
        }
    }

    private static TextStyle elementStyle(Element element,TextStyle inherited) {
        TextStyle style=new TextStyle(inherited);
        String tag=element.normalName();
        if(isBlock(tag)){ style.spaceBefore=0; style.spaceAfter=0; }
        if("b".equals(tag) || "strong".equals(tag)){ style.bold=true; }
        if("i".equals(tag) || "em".equals(tag)){ style.italic=true; }
        if("u".equals(tag)){ style.underline=true; }
        if("s".equals(tag) || "strike".equals(tag) || "del".equals(tag)){ style.strike=true; }
        if("sub".equals(tag) || "sup".equals(tag)){
            style.script="sub".equals(tag) ? -1 : 1;
            style.fontSize*=.83f;
        }
        if(tag.matches("h[1-6]")){
            style.fontSize*=new float[]{2,1.5f,1.17f,1,.83f,.67f}[tag.charAt(1)-'1'];
            style.bold=true; style.lineHeight=1.2f;
        }
        if("blockquote".equals(tag)){ style.leftIndent+=style.fontSize*2; }
        if("a".equals(tag)){
            style.linkUrl=safeLink(element.attr("href"));
            style.color="337AB7"; style.underline=true;
        }
        if(element.hasAttr("align")){ style.align=element.attr("align").toLowerCase(Locale.ROOT); }
        if("font".equals(tag)){
            if(element.hasAttr("face")){ style.fontFamily=family(element.attr("face")); }
            if(element.hasAttr("color")){ style.color=color(element.attr("color"),style.color); }
            try {
                int size=Integer.parseInt(element.attr("size"));
                if(size>=1 && size<=7){ style.fontSize=new float[]{8,10,12,14,18,24,36}[size-1]; }
            }catch(NumberFormatException ignored) { }
        }
        Map<String,String> css=new LinkedHashMap<String,String>();
        for(String declaration:element.attr("style").split(";")){
            int colon=declaration.indexOf(':');
            if(colon>0){ css.put(declaration.substring(0,colon).trim().toLowerCase(Locale.ROOT),
                    declaration.substring(colon+1).trim().replace("!important","").trim()); }
        }
        String size=css.get("font-size");
        if(size!=null){ style.fontSize=positive(fontSize(size,inherited.fontSize),style.fontSize); }
        if(css.containsKey("font-family")){ style.fontFamily=family(css.get("font-family")); }
        if(css.containsKey("font-weight")){
            String weight=css.get("font-weight");
            style.bold="bold".equals(weight) || "bolder".equals(weight) || weight.matches("[6-9]00");
        }
        if(css.containsKey("font-style")){ style.italic=!"normal".equals(css.get("font-style")); }
        if(css.containsKey("text-decoration")){
            String decoration=css.get("text-decoration");
            style.underline=decoration.contains("underline"); style.strike=decoration.contains("line-through");
        }
        if(css.containsKey("color")){ style.color=color(css.get("color"),style.color); }
        if(css.containsKey("background-color")){ style.backgroundColor=color(css.get("background-color"),null); }
        if(css.containsKey("text-align")){ style.align=css.get("text-align"); }
        if(css.containsKey("vertical-align")){
            String align=css.get("vertical-align");
            style.script="sub".equals(align) ? -1 : "super".equals(align) ? 1 : 0;
        }
        if(css.containsKey("line-height")){
            String height=css.get("line-height");
            try {
                style.lineHeight=positive(Float.parseFloat(height),style.lineHeight);
            }catch(NumberFormatException ex){
                style.lineHeight=positive(length(height,style.fontSize)/style.fontSize,style.lineHeight);
            }
        }
        if(css.containsKey("text-indent")){ style.firstLineIndent=length(css.get("text-indent"),style.fontSize); }
        if(css.containsKey("margin")){
            String[] margins=css.get("margin").split("\\s+");
            if(margins.length>0){
                style.spaceBefore=Math.max(0,length(margins[0],style.fontSize));
                style.spaceAfter=Math.max(0,length(margins.length>2 ? margins[2] : margins[0],style.fontSize));
                style.leftIndent+=Math.max(0,length(margins.length>3 ? margins[3] : margins.length>1 ? margins[1] : margins[0],style.fontSize));
            }
        }
        if(css.containsKey("margin-top")){ style.spaceBefore=Math.max(0,length(css.get("margin-top"),style.fontSize)); }
        if(css.containsKey("margin-bottom")){ style.spaceAfter=Math.max(0,length(css.get("margin-bottom"),style.fontSize)); }
        if(css.containsKey("margin-left")){ style.leftIndent+=Math.max(0,length(css.get("margin-left"),style.fontSize)); }
        return style;
    }

    private static String family(String value) {
        return value.split(",")[0].replace("\"","").replace("'","").trim();
    }
    private static boolean isBlock(String tag) {
        return "p".equals(tag) || "div".equals(tag) || "blockquote".equals(tag)
                || "li".equals(tag) || tag.matches("h[1-6]");
    }
    private static float positive(float value,float fallback) { return Float.isFinite(value) && value>0 ? value : fallback; }
    private static float fontSize(String value,float inherited) {
        String size=value.toLowerCase(Locale.ROOT);
        String[] names={"xx-small","x-small","small","medium","large","x-large","xx-large"};
        float[] points={7,8,10,12,14,18,24};
        for(int i=0;i<names.length;i++){ if(names[i].equals(size)){ return points[i]; } }
        return length(size,inherited);
    }
    private static float length(String value,float reference) {
        try {
            String text=value.trim().toLowerCase(Locale.ROOT);
            float factor=1;
            if(text.endsWith("rem")){ factor=12; text=text.substring(0,text.length()-3); }
            else if(text.endsWith("em")){ factor=reference; text=text.substring(0,text.length()-2); }
            else if(text.endsWith("px")){ factor=.75f; text=text.substring(0,text.length()-2); }
            else if(text.endsWith("pt")){ text=text.substring(0,text.length()-2); }
            else if(text.endsWith("in")){ factor=72; text=text.substring(0,text.length()-2); }
            else if(text.endsWith("cm")){ factor=72/2.54f; text=text.substring(0,text.length()-2); }
            else if(text.endsWith("mm")){ factor=72/25.4f; text=text.substring(0,text.length()-2); }
            else if(text.endsWith("pc")){ factor=12; text=text.substring(0,text.length()-2); }
            else if(text.endsWith("%")){ factor=reference/100; text=text.substring(0,text.length()-1); }
            float result=Float.parseFloat(text)*factor;
            return Float.isFinite(result) ? Math.max(-10000,Math.min(10000,result)) : 0;
        }catch(NumberFormatException ex){ return 0; }
    }
    private static String safeLink(String value) {
        String link=value.trim();
        if(link.isEmpty() || link.matches("(?s).*[\\p{Cntrl}].*") || link.contains("${")){ return null; }
        int colon=link.indexOf(':');
        if(colon>=0 && !link.substring(0,colon).matches("(?i)https?|mailto")){ return null; }
        return link;
    }
    private static String color(String value,String fallback) {
        String text=value.trim().toLowerCase(Locale.ROOT);
        try {
            if(text.matches("[0-9 ]+,[0-9 ]+,[0-9 ]+")){ text="rgb("+text+")"; }
            if(text.startsWith("rgb(") || text.startsWith("rgba(")){
                String[] channels=text.substring(text.indexOf('(')+1,text.lastIndexOf(')')).split(",");
                int[] rgb=new int[3];
                for(int i=0;i<3;i++){
                    String channel=channels[i].trim();
                    float number=Float.parseFloat(channel.replace("%",""));
                    rgb[i]=Math.max(0,Math.min(255,Math.round(channel.endsWith("%") ? number*2.55f : number)));
                }
                return String.format(Locale.ROOT,"%02X%02X%02X",rgb[0],rgb[1],rgb[2]);
            }
            if(text.matches("#[0-9a-f]{3}")){
                text="#"+text.charAt(1)+text.charAt(1)+text.charAt(2)+text.charAt(2)+text.charAt(3)+text.charAt(3);
            }
            if(text.matches("#[0-9a-f]{6}")){ return text.substring(1).toUpperCase(Locale.ROOT); }
            Color color=new javax.swing.text.html.StyleSheet().stringToColor(text);
            if(color!=null){ return String.format(Locale.ROOT,"%02X%02X%02X",color.getRed(),color.getGreen(),color.getBlue()); }
        }catch(RuntimeException ignored) { }
        return fallback;
    }
}
