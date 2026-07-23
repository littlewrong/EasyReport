package cn.easyreport.utils;

public class RmbChangeUtils {
    /**
     * @param number
     */
    public static String numToChinese(String number){
        String s1="零壹贰叁肆伍陆柒捌玖";
        String s4="分角整元拾佰仟万拾佰仟亿拾佰仟";
        String temp="";
        String result="";
        if (number==null) return "输入字串不是数字串只能包括以下字符（'0'～'9'，'.')，输入字串最大只能精确到仟亿，小数点只能两位！";
        temp=number.trim();
        float f;
        try{
            f=Float.parseFloat(temp);
        }catch(Exception e){return "输入字串不是数字串只能包括以下字符（'0'～'9'，'.')，输入字串最大只能精确到仟亿，小数点只能两位！";}
        int len=0;
        if (temp.indexOf(".")==-1) len=temp.length();
        else len=temp.indexOf(".");
        if(len>s4.length()-3) return("输入字串最大只能精确到仟亿，小数点只能两位！");
        int n1,n2=0;
        String num="";
        String unit="";
        for(int i=0;i<temp.length();i++){
            if(i>len+2){break;}
            if(i==len) {continue;}
            n1=Integer.parseInt(String.valueOf(temp.charAt(i)));
            num=s1.substring(n1,n1+1);
            n1=len-i+2;
            unit=s4.substring(n1,n1+1);
//            result=result.concat(num).concat(unit);
//            result=result.concat(num).concat("\u2007\u2007");
            if(unit.equals("分")){
                result=result.concat(num).concat("   ");
            }else if(unit.equals("角")){
                result=result.concat(num).concat("  ");
            }else if(unit.equals("拾")){
                result=result.concat(num).concat("  ");
            }else if(unit.equals("仟")){
                result=result.concat(num).concat("  ");
            }
            else {
                result=result.concat(num).concat("   ");
            }
        }
        if ((len==temp.length())||(len==temp.length()-1)) result=result.concat("整");
        if (len==temp.length()-2) result=result.concat("零分");
        return result;
    }
}
