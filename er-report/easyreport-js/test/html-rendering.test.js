// Run with: node test/html-rendering.test.js (no browser or new dependencies).
require('babel-register')({presets:[['env',{targets:{node:'current'}}]],babelrc:false});
const assert=require('assert');
const {buildNewCellDef,tableToXml}=require('../src/Utils.js');
const BaseValueEditor=require('../src/panel/property/BaseValueEditor.js').default;
const SimpleValueEditor=require('../src/panel/property/SimpleValueEditor.js').default;
global.window={i18n:require('../src/i18n/designer.json')};
let dirtyCount=0;
global.$=()=>({removeClass(){dirtyCount++;}});

const cells=[
    Object.assign(buildNewCellDef(1,1),{value:{type:'simple',value:'<p style="color:red">原文 & ]]> 内容</p>'}}),
    Object.assign(buildNewCellDef(1,2),{value:{type:'expression',value:'"<b>表达式 ]]> 内容</b>"'}}),
    Object.assign(buildNewCellDef(1,3),{html:true,value:{type:'image',source:'text',value:'logo.png'}}),
    buildNewCellDef(2,1)
];
const context={
    cellsMap:new Map(cells.map(cell=>[cell.rowNumber+','+cell.columnNumber,cell])),
    rowHeaders:[],
    reportDef:{datasources:[],paper:{}},
    getCell(row,col){return this.cellsMap.get((row+1)+','+(col+1));},
    getCellName(row,col){return String.fromCharCode(65+col)+(row+1);},
    hot:{
        countRows(){return 2;},
        countCols(){return 3;},
        getSettings(){return {mergeCells:[]};},
        getRowHeight(){return 25;},
        getColWidth(){return 100;}
    }
};
let checked=false,wrapVisible=true;
const editor=Object.assign(new BaseValueEditor(),{
    context,cellDef:cells[0],rowIndex:1,colIndex:2,row2Index:0,col2Index:0,
    htmlRenderingEditor:{prop(name,value){assert.strictEqual(name,'checked');checked=value;}},
    wrapGroup:{toggle(visible){wrapVisible=visible;}}
});

// A reversed, mixed selection changes only text/expression cells, including
// empty text cells, and never dereferences cells absent from the report.
editor._refreshHtmlRendering();
assert.strictEqual(checked,false);
editor._setHtmlRendering(true);
assert.strictEqual(cells[0].html,true);
assert.strictEqual(cells[1].html,true);
assert.strictEqual(cells[2].html,true);
assert.strictEqual(cells[3].html,true);
assert.strictEqual(checked,true);
assert.strictEqual(wrapVisible,false);
assert.strictEqual(dirtyCount,1);

let xml=decodeURIComponent(tableToXml(context));
function cellXml(name){
    return xml.match(new RegExp('<cell\\s[^>]*name="'+name+'"[^>]*>[\\s\\S]*?</cell>'))[0];
}
for(const name of ['A1','B1','A2']) assert(cellXml(name).includes(' html="true"'));
assert(!cellXml('C1').includes(' html='));
// Split CDATA terminators losslessly so literal HTML remains valid XML.
for(const [name,tag,cell] of [['A1','simple-value',cells[0]],['B1','expression-value',cells[1]]]){
    const savedValue=cellXml(name).match(new RegExp('<'+tag+'>([\\s\\S]*?)</'+tag+'>'))[1];
    const decoded=Array.from(savedValue.matchAll(/<!\[CDATA\[([\s\S]*?)\]\]>/g),match=>match[1]).join('');
    assert.strictEqual(decoded,cell.value.value);
}

editor._setHtmlRendering(false);
assert.strictEqual(cells[0].html,false);
assert.strictEqual(cells[1].html,false);
assert.strictEqual(cells[2].html,true);
assert.strictEqual(wrapVisible,true);
xml=decodeURIComponent(tableToXml(context));
assert(!xml.includes(' html='));
assert(xml.includes('<simple-value>'));
assert(xml.includes('<expression-value>'));

// Opening another text cell restores its switch state and selection bounds;
// legacy reports without an HTML attribute always start unchecked.
const simpleEditor=Object.assign(Object.create(SimpleValueEditor.prototype),editor,{
    container:{show(){}},editor:{val(){}},lineHeightEditor:{val(){}}
});
delete cells[3].html;
simpleEditor.show(cells[3],1,0,1,1);
assert.strictEqual(checked,false);
assert.strictEqual(simpleEditor.row2Index,1);
assert.strictEqual(simpleEditor.col2Index,1);
simpleEditor._setHtmlRendering(true);
assert.strictEqual(cells[3].html,true);
assert.strictEqual(cells[0].html,false);
simpleEditor.show(cells[0],0,0);
assert.strictEqual(checked,false);
simpleEditor._setHtmlRendering(true);
assert.strictEqual(cells[0].html,true);
assert.strictEqual(cells[1].html,false);

for(const locale of ['designer.json','designer_en.json']){
    const text=require('../src/i18n/'+locale).property.base;
    assert(text.htmlRendering);
    assert(text.htmlRenderingTip);
}

// Load the actual text renderer without browser-only chart/slash packages.
// Those branches are unrelated to source text and are never invoked here.
const widgetPaths=['CrossTabWidget','ChartWidget'].map(name=>require.resolve('../src/widget/'+name+'.js'));
const originalWidgets=widgetPaths.map(path=>require.cache[path]);
for(const path of widgetPaths) require.cache[path]={id:path,filename:path,loaded:true,exports:{__esModule:true,default:class {}}};
const {afterRenderer}=require('../src/table/CellRenderer.js');
widgetPaths.forEach((path,index)=>{
    if(originalWidgets[index]) require.cache[path]=originalWidgets[index];
    else delete require.cache[path];
});
function escapeText(value){
    return value.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}
class TextCell {
    constructor(text){this.textValue=text;this.htmlValue=escapeText(text);this.properties={};}
    text(value){
        if(value===undefined) return this.textValue;
        this.textValue=value;
        this.htmlValue=escapeText(value);
        return this;
    }
    html(value){
        if(value===undefined) return this.htmlValue;
        // Fail immediately if raw source markup is ever offered to the DOM,
        // even if a later rendering step would remove the resulting nodes.
        assert(!/<(?!br>)[^>]*>/i.test(value),'Raw source HTML must never be inserted');
        this.htmlValue=value;
        this.textValue=value.replace(/<br>/g,'\n').replace(/&(amp|lt|gt|nbsp|#\d+|#x[\da-f]+);/gi,entity=>{
            if(entity==='&amp;') return '&';
            if(entity==='&lt;') return '<';
            if(entity==='&gt;') return '>';
            if(entity==='&nbsp;') return ' ';
            return String.fromCodePoint(parseInt(entity.slice(2,-1).replace(/^x/i,''),/^&#x/i.test(entity) ? 16 : 10));
        });
        return this;
    }
    prop(name,value){this.properties[name]=value;return this;}
    css(){return this;}
    prepend(){return this;}
}
global.$=value=>value;
for(const type of ['simple','expression']){
    for(const html of [false,true]){
        for(const source of [
            '<script>window.sourceExecuted=true</script>',
            '<img src="x" onerror="window.sourceExecuted=true">',
            '&lt;b&gt;文字&lt;/b&gt; &amp; &#60;img&#62;'
        ]){
            for(const initialText of ['',source]){
                const cell={value:{type,value:source},html,cellStyle:{},expand:'None'};
                const td=new TextCell(initialText);
                afterRenderer.call({context:{getCell(){return cell;}}},td,0,0,null,initialText,{});
                assert.strictEqual(td.text(),source);
                assert.strictEqual(td.properties.title,source);
            }
        }
    }
}
console.log('HTML rendering editor and XML tests passed.');
