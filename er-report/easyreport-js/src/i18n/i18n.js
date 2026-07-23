/**
 * Created by Jacky.Gao on 2017-10-01.
 */
import defaultI18nJsonData from './designer.json';
import en18nJsonData from './designer_en.json';
export default function buildLocal(){
    let language=window.navigator.language || window.navigator.browserLanguage;
    if(!language){
        language='zh-cn';
    }
    language=language.toLowerCase();
    // Treat any Chinese locale (zh, zh-cn, zh-tw, zh-hk, etc.) as Chinese; otherwise fallback to English.
    window.i18n=language.startsWith('zh') ? defaultI18nJsonData : en18nJsonData;
}
