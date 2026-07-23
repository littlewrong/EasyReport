/*******************************************************************************
 * Copyright 2017 Bstek
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package cn.easyreport.build;

/**
 * @author Jacky.gao
 * @since 2017年6月19日
 */
public class Splash {
	public void doPrint(){
		StringBuilder sb=new StringBuilder();
		sb.append("\n");
		sb.append(" _____                 ____                        _   ");
		sb.append("\n");
		sb.append("| ____|__ _ ___ _   _ |  _ \\ ___ _ __   ___  _ __| |_ ");
		sb.append("\n");
		sb.append("|  _| / _` / __| | | || |_) / _ \\ '_ \\ / _ \\| '__| __|");
		sb.append("\n");
		sb.append("| |__| (_| \\__ \\ |_| ||  _ <  __/ |_) | (_) | |  | |_ ");
		sb.append("\n");
		sb.append("|_____\\__,_|___/\\__, ||_| \\_\\___| .__/ \\___/|_|   \\__|");
		sb.append("\n");
		sb.append("               |___/            |_|                    ");
		sb.append("\n");
		sb.append("...........................................................................");
		sb.append("\n");
		sb.append(".  EasyReport, a report engine based on UReport2.                        .");
		sb.append("\n");
		sb.append(".  Easy to use, high-performance, with browser-based-designer.           .");
		sb.append("\n");
		sb.append("...........................................................................");
		sb.append("\n");
		System.out.println(sb.toString());
	}
}
