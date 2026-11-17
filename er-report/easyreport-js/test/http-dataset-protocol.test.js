// Exercise the real dialog, edit callback and report XML serializer without a browser.
require('babel-register')({presets:[['env',{targets:{node:'current'}}]],babelrc:false});
const assert=require('assert');

class Element {
    constructor(markup='') { this.markup=markup; this.children=[]; this.value=''; }
    append(child) { this.children.push(child); return this; }
    find() { return new Element(); }
    val(value) { if(arguments.length===0) return this.value; this.value=value; return this; }
    html() { return this; }
    empty() { return this; }
    modal() { return this; }
    removeClass() { return this; }
    click(callback) { this.clickCallback=callback; return this; }
}
global.window={i18n:require('../src/i18n/designer.json')};
global.$=markup=>new Element(markup);
let datasetMenu;
$.contextMenu=options=>{datasetMenu=options;};

const {tableToXml}=require('../src/Utils.js');
const HttpDatasetDialog=require('../src/dialog/HttpDatasetDialog.js').default;

// Tree construction does not need browser widgets for this edit callback.
// Keep the actual tree method and its callback; replace its modal and unrelated base widget.
let editCallback;
const replacementModules=[
    [require.resolve('../src/dialog/HttpDatasetDialog.js'),class {
        show(callback) { editCallback=callback; }
    }],
    [require.resolve('../src/tree/BaseTree.js'),class {}]
];
const originals=replacementModules.map(([path])=>require.cache[path]);
replacementModules.forEach(([path,type])=>{
    require.cache[path]={id:path,filename:path,loaded:true,exports:{__esModule:true,default:type}};
});
const HttpTree=require('../src/tree/HttpTree.js').default;
replacementModules.forEach(([path],index)=>{
    if(originals[index]) require.cache[path]=originals[index];
    else delete require.cache[path];
});

function dialog() {
    const result=Object.create(HttpDatasetDialog.prototype);
    result.data={parameters:[],fields:[]};
    result.datasources=[];
    result.dialog=new Element();
    for(const name of ['name','url','method','protocolForce','requestBody','dataPath']) {
        result[name+'Editor']=new Element();
    }
    return result;
}

function serialize(dataset) {
    const context={
        cellsMap:new Map(),rowHeaders:[],
        reportDef:{datasources:[{name:'API',type:'http',datasets:[dataset]}],paper:{}},
        hot:{countRows(){return 0;},countCols(){return 0;},getSettings(){return {mergeCells:[]};}}
    };
    return decodeURIComponent(tableToXml(context));
}

const emptyDialog=dialog();
emptyDialog.show(()=>{});
assert.strictEqual(emptyDialog.protocolForceEditor.val(),'auto','new datasets must follow the current report origin');
emptyDialog.show(()=>{},{name:'legacy',url:'/executorapi/run'});
assert.strictEqual(emptyDialog.protocolForceEditor.val(),'auto','old datasets without a mode must default to auto');

for(const mode of ['auto','http','https']) {
    const original={name:'data',url:'/executorapi/run',method:'POST',protocolForce:'https',parameters:[],fields:[]};
    const editor=dialog();
    let submitted;
    const footer=new Element();
    editor.initButton(footer);
    editor.show(dataset=>{submitted=dataset;},original);
    editor.protocolForceEditor.val(mode);
    footer.children.find(button=>button.markup.includes('>确定<')).clickCallback();
    assert.strictEqual(submitted.protocolForce,mode,'the dialog must submit the selected mode');

    const tree=Object.assign(Object.create(HttpTree.prototype),{
        datasetUL:new Element(),datasets:[original],attachEvent(){},
        buildFileds(dataset) { assert.strictEqual(dataset,original); }
    });
    tree.addDataset(original);
    datasetMenu.callback('edit',{});
    editCallback(submitted);
    assert.strictEqual(original.protocolForce,mode,'editing an existing dataset must update its mode');

    const xml=serialize(original);
    const datasetXml=xml.match(/<dataset\s[^>]*>/)[0];
    assert.strictEqual(datasetXml.match(/protocolForce="([^"]+)"/)[1],mode,'the report must persist its mode');
    assert(datasetXml.includes('url="/executorapi/run"'),'relative URLs must stay relative in the saved report');
    assert(!xml.includes('https://'),'saving auto must not capture the current deployment domain');

    // Model the data returned by the backend XML parser when opening the report again.
    const reloadedMode=datasetXml.match(/protocolForce="([^"]+)"/)[1];
    editor.show(()=>{},Object.assign({},original,{protocolForce:reloadedMode}));
    assert.strictEqual(editor.protocolForceEditor.val(),mode,'reopening must retain an explicit protocol mode');
}

for(const missing of [undefined,null,'','invalid" mode']) {
    const xml=serialize({name:'legacy',url:'/executorapi/run',protocolForce:missing,parameters:[],fields:[]});
    assert(xml.includes(' protocolForce="auto"'),'missing or invalid modes must serialize as auto');
}

console.log('HTTP dataset protocol tests passed (default, edit, save and reopen for auto/http/https).');
