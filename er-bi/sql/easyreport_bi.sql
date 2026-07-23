/*
 Navicat Premium Data Transfer

 Source Server         : localhost
 Source Server Type    : MySQL
 Source Server Version : 80044 (8.0.44)
 Source Host           : localhost:3306
 Source Schema         : easyreport_bi

 Target Server Type    : MySQL
 Target Server Version : 80044 (8.0.44)
 File Encoding         : 65001

 Date: 05/07/2026 17:44:22
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for bi_db_connector
-- ----------------------------
DROP TABLE IF EXISTS `bi_db_connector`;
CREATE TABLE `bi_db_connector`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `db_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `host` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `port` int NOT NULL,
  `database` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `username` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `password` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `extra` json NULL,
  `status` int NOT NULL,
  `remark` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of bi_db_connector
-- ----------------------------
INSERT INTO `bi_db_connector` VALUES (1, 'MySQL', 'MySQL', 'localhost', 3306, 'easyreport', 'root', 'er888888', 'null', 1, NULL, '2026-06-14 19:53:54', '2026-06-14 19:53:54');

-- ----------------------------
-- Table structure for bi_http_datasource
-- ----------------------------
DROP TABLE IF EXISTS `bi_http_datasource`;
CREATE TABLE `bi_http_datasource`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `connector_id` bigint NULL DEFAULT NULL,
  `request_method` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `request_url` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `request_headers` json NULL,
  `request_body` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `status` int NOT NULL,
  `remark` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `connector_id`(`connector_id` ASC) USING BTREE,
  CONSTRAINT `bi_http_datasource_ibfk_1` FOREIGN KEY (`connector_id`) REFERENCES `bi_db_connector` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of bi_http_datasource
-- ----------------------------

-- ----------------------------
-- Table structure for bi_http_datasource_version
-- ----------------------------
DROP TABLE IF EXISTS `bi_http_datasource_version`;
CREATE TABLE `bi_http_datasource_version`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `datasource_id` bigint NOT NULL,
  `version_no` int NOT NULL,
  `snapshot` json NOT NULL,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of bi_http_datasource_version
-- ----------------------------

-- ----------------------------
-- Table structure for bi_project
-- ----------------------------
DROP TABLE IF EXISTS `bi_project`;
CREATE TABLE `bi_project`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `state` int NOT NULL,
  `content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `index_image` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
  `remarks` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `create_user_id` bigint NULL DEFAULT NULL,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of bi_project
-- ----------------------------
INSERT INTO `bi_project` VALUES (1, '测试大屏', 1, '{\n  \"editCanvasConfig\": {\n    \"projectName\": \"测试大屏\",\n    \"width\": 1920,\n    \"height\": 1080,\n    \"filterShow\": false,\n    \"hueRotate\": 0,\n    \"saturate\": 1,\n    \"contrast\": 1,\n    \"brightness\": 1,\n    \"opacity\": 1,\n    \"rotateZ\": 0,\n    \"rotateX\": 0,\n    \"rotateY\": 0,\n    \"skewX\": 0,\n    \"skewY\": 0,\n    \"blendMode\": \"normal\",\n    \"background\": null,\n    \"backgroundImage\": null,\n    \"selectColor\": true,\n    \"chartThemeColor\": \"dark\",\n    \"chartCustomThemeColorInfo\": null,\n    \"chartThemeSetting\": {\n      \"title\": {\n        \"show\": true,\n        \"textStyle\": {\n          \"color\": \"#BFBFBF\",\n          \"fontSize\": 18\n        },\n        \"subtextStyle\": {\n          \"color\": \"#A2A2A2\",\n          \"fontSize\": 14\n        }\n      },\n      \"xAxis\": {\n        \"show\": true,\n        \"name\": \"\",\n        \"nameGap\": 15,\n        \"nameTextStyle\": {\n          \"color\": \"#B9B8CE\",\n          \"fontSize\": 12\n        },\n        \"inverse\": false,\n        \"axisLabel\": {\n          \"show\": true,\n          \"fontSize\": 12,\n          \"color\": \"#B9B8CE\",\n          \"rotate\": 0\n        },\n        \"position\": \"bottom\",\n        \"axisLine\": {\n          \"show\": true,\n          \"lineStyle\": {\n            \"color\": \"#B9B8CE\",\n            \"width\": 1\n          },\n          \"onZero\": true\n        },\n        \"axisTick\": {\n          \"show\": true,\n          \"length\": 5\n        },\n        \"splitLine\": {\n          \"show\": false,\n          \"lineStyle\": {\n            \"color\": \"#484753\",\n            \"width\": 1,\n            \"type\": \"solid\"\n          }\n        }\n      },\n      \"yAxis\": {\n        \"show\": true,\n        \"name\": \"\",\n        \"nameGap\": 15,\n        \"nameTextStyle\": {\n          \"color\": \"#B9B8CE\",\n          \"fontSize\": 12\n        },\n        \"inverse\": false,\n        \"axisLabel\": {\n          \"show\": true,\n          \"fontSize\": 12,\n          \"color\": \"#B9B8CE\",\n          \"rotate\": 0\n        },\n        \"position\": \"left\",\n        \"axisLine\": {\n          \"show\": true,\n          \"lineStyle\": {\n            \"color\": \"#B9B8CE\",\n            \"width\": 1\n          },\n          \"onZero\": true\n        },\n        \"axisTick\": {\n          \"show\": true,\n          \"length\": 5\n        },\n        \"splitLine\": {\n          \"show\": true,\n          \"lineStyle\": {\n            \"color\": \"#484753\",\n            \"width\": 1,\n            \"type\": \"solid\"\n          }\n        }\n      },\n      \"legend\": {\n        \"show\": true,\n        \"type\": \"scroll\",\n        \"x\": \"center\",\n        \"y\": \"top\",\n        \"icon\": \"circle\",\n        \"orient\": \"horizontal\",\n        \"textStyle\": {\n          \"color\": \"#B9B8CE\",\n          \"fontSize\": 18\n        },\n        \"itemHeight\": 15,\n        \"itemWidth\": 15,\n        \"pageTextStyle\": {\n          \"color\": \"#B9B8CE\"\n        }\n      },\n      \"grid\": {\n        \"show\": false,\n        \"left\": \"10%\",\n        \"top\": \"60\",\n        \"right\": \"10%\",\n        \"bottom\": \"60\"\n      },\n      \"dataset\": null,\n      \"renderer\": \"svg\"\n    },\n    \"vChartThemeName\": \"vScreenVolcanoBlue\",\n    \"previewScaleType\": \"fit\"\n  },\n  \"componentList\": [\n    {\n      \"id\": \"id_19bhu7k3dt8g00\",\n      \"isGroup\": false,\n      \"attr\": {\n        \"x\": 142,\n        \"y\": 44,\n        \"w\": 500,\n        \"h\": 300,\n        \"offsetX\": 0,\n        \"offsetY\": 0,\n        \"zIndex\": -1\n      },\n      \"styles\": {\n        \"filterShow\": false,\n        \"hueRotate\": 0,\n        \"saturate\": 1,\n        \"contrast\": 1,\n        \"brightness\": 1,\n        \"opacity\": 1,\n        \"rotateZ\": 0,\n        \"rotateX\": 0,\n        \"rotateY\": 0,\n        \"skewX\": 0,\n        \"skewY\": 0,\n        \"blendMode\": \"normal\",\n        \"animations\": []\n      },\n      \"preview\": {\n        \"overFlowHidden\": false\n      },\n      \"status\": {\n        \"lock\": false,\n        \"hide\": false\n      },\n      \"request\": {\n        \"requestDataType\": 0,\n        \"requestHttpType\": \"get\",\n        \"requestUrl\": \"\",\n        \"requestInterval\": null,\n        \"requestIntervalUnit\": \"second\",\n        \"requestContentType\": 0,\n        \"requestParamsBodyType\": \"none\",\n        \"requestSQLContent\": {\n          \"sql\": \"select * from  where\"\n        },\n        \"requestParams\": {\n          \"Body\": {\n            \"form-data\": {},\n            \"x-www-form-urlencoded\": {},\n            \"json\": \"\",\n            \"xml\": \"\"\n          },\n          \"Header\": {},\n          \"Params\": {}\n        }\n      },\n      \"filter\": null,\n      \"events\": {\n        \"baseEvent\": {\n          \"click\": null,\n          \"dblclick\": null,\n          \"mouseenter\": null,\n          \"mouseleave\": null\n        },\n        \"advancedEvents\": {\n          \"vnodeMounted\": null,\n          \"vnodeBeforeMount\": null\n        },\n        \"interactEvents\": []\n      },\n      \"key\": \"BarCommon\",\n      \"chartConfig\": {\n        \"key\": \"BarCommon\",\n        \"chartKey\": \"VBarCommon\",\n        \"conKey\": \"VCBarCommon\",\n        \"title\": \"柱状图\",\n        \"category\": \"Bars\",\n        \"categoryName\": \"柱状图\",\n        \"package\": \"Charts\",\n        \"chartFrame\": \"echarts\",\n        \"image\": \"bar_x.png\"\n      },\n      \"option\": {\n        \"legend\": {\n          \"show\": true,\n          \"type\": \"scroll\",\n          \"x\": \"center\",\n          \"y\": \"top\",\n          \"icon\": \"circle\",\n          \"orient\": \"horizontal\",\n          \"textStyle\": {\n            \"color\": \"#B9B8CE\",\n            \"fontSize\": 18\n          },\n          \"itemHeight\": 15,\n          \"itemWidth\": 15,\n          \"pageTextStyle\": {\n            \"color\": \"#B9B8CE\"\n          }\n        },\n        \"xAxis\": {\n          \"show\": true,\n          \"name\": \"\",\n          \"nameGap\": 15,\n          \"nameTextStyle\": {\n            \"color\": \"#B9B8CE\",\n            \"fontSize\": 12\n          },\n          \"inverse\": false,\n          \"axisLabel\": {\n            \"show\": true,\n            \"fontSize\": 12,\n            \"color\": \"#B9B8CE\",\n            \"rotate\": 0\n          },\n          \"position\": \"bottom\",\n          \"axisLine\": {\n            \"show\": true,\n            \"lineStyle\": {\n              \"color\": \"#B9B8CE\",\n              \"width\": 1\n            },\n            \"onZero\": true\n          },\n          \"axisTick\": {\n            \"show\": true,\n            \"length\": 5\n          },\n          \"splitLine\": {\n            \"show\": false,\n            \"lineStyle\": {\n              \"color\": \"#484753\",\n              \"width\": 1,\n              \"type\": \"solid\"\n            }\n          },\n          \"type\": \"category\"\n        },\n        \"yAxis\": {\n          \"show\": true,\n          \"name\": \"\",\n          \"nameGap\": 15,\n          \"nameTextStyle\": {\n            \"color\": \"#B9B8CE\",\n            \"fontSize\": 12\n          },\n          \"inverse\": false,\n          \"axisLabel\": {\n            \"show\": true,\n            \"fontSize\": 12,\n            \"color\": \"#B9B8CE\",\n            \"rotate\": 0\n          },\n          \"position\": \"left\",\n          \"axisLine\": {\n            \"show\": true,\n            \"lineStyle\": {\n              \"color\": \"#B9B8CE\",\n              \"width\": 1\n            },\n            \"onZero\": true\n          },\n          \"axisTick\": {\n            \"show\": true,\n            \"length\": 5\n          },\n          \"splitLine\": {\n            \"show\": true,\n            \"lineStyle\": {\n              \"color\": \"#484753\",\n              \"width\": 1,\n              \"type\": \"solid\"\n            }\n          },\n          \"type\": \"value\"\n        },\n        \"grid\": {\n          \"show\": false,\n          \"left\": \"10%\",\n          \"top\": \"60\",\n          \"right\": \"10%\",\n          \"bottom\": \"60\"\n        },\n        \"tooltip\": {\n          \"show\": true,\n          \"trigger\": \"axis\",\n          \"axisPointer\": {\n            \"show\": true,\n            \"type\": \"shadow\"\n          }\n        },\n        \"dataset\": {\n          \"dimensions\": [\n            \"product\",\n            \"data1\",\n            \"data2\"\n          ],\n          \"source\": [\n            {\n              \"product\": \"Mon\",\n              \"data1\": 120,\n              \"data2\": 130\n            },\n            {\n              \"product\": \"Tue\",\n              \"data1\": 200,\n              \"data2\": 130\n            },\n            {\n              \"product\": \"Wed\",\n              \"data1\": 150,\n              \"data2\": 312\n            },\n            {\n              \"product\": \"Thu\",\n              \"data1\": 80,\n              \"data2\": 268\n            },\n            {\n              \"product\": \"Fri\",\n              \"data1\": 70,\n              \"data2\": 155\n            },\n            {\n              \"product\": \"Sat\",\n              \"data1\": 110,\n              \"data2\": 117\n            },\n            {\n              \"product\": \"Sun\",\n              \"data1\": 130,\n              \"data2\": 160\n            }\n          ]\n        },\n        \"series\": [\n          {\n            \"type\": \"bar\",\n            \"barWidth\": 15,\n            \"label\": {\n              \"show\": true,\n              \"position\": \"top\",\n              \"color\": \"#fff\",\n              \"fontSize\": 12\n            },\n            \"itemStyle\": {\n              \"color\": null,\n              \"borderRadius\": 2\n            }\n          },\n          {\n            \"type\": \"bar\",\n            \"barWidth\": 15,\n            \"label\": {\n              \"show\": true,\n              \"position\": \"top\",\n              \"color\": \"#fff\",\n              \"fontSize\": 12\n            },\n            \"itemStyle\": {\n              \"color\": null,\n              \"borderRadius\": 2\n            }\n          }\n        ],\n        \"backgroundColor\": \"rgba(0,0,0,0)\"\n      }\n    },\n    {\n      \"id\": \"id_ytz518xx6rk00\",\n      \"isGroup\": false,\n      \"attr\": {\n        \"x\": 710,\n        \"y\": 43,\n        \"w\": 500,\n        \"h\": 300,\n        \"offsetX\": 0,\n        \"offsetY\": 0,\n        \"zIndex\": -1\n      },\n      \"styles\": {\n        \"filterShow\": false,\n        \"hueRotate\": 0,\n        \"saturate\": 1,\n        \"contrast\": 1,\n        \"brightness\": 1,\n        \"opacity\": 1,\n        \"rotateZ\": 0,\n        \"rotateX\": 0,\n        \"rotateY\": 0,\n        \"skewX\": 0,\n        \"skewY\": 0,\n        \"blendMode\": \"normal\",\n        \"animations\": []\n      },\n      \"preview\": {\n        \"overFlowHidden\": false\n      },\n      \"status\": {\n        \"lock\": false,\n        \"hide\": false\n      },\n      \"request\": {\n        \"requestDataType\": 0,\n        \"requestHttpType\": \"get\",\n        \"requestUrl\": \"\",\n        \"requestInterval\": null,\n        \"requestIntervalUnit\": \"second\",\n        \"requestContentType\": 0,\n        \"requestParamsBodyType\": \"none\",\n        \"requestSQLContent\": {\n          \"sql\": \"select * from  where\"\n        },\n        \"requestParams\": {\n          \"Body\": {\n            \"form-data\": {},\n            \"x-www-form-urlencoded\": {},\n            \"json\": \"\",\n            \"xml\": \"\"\n          },\n          \"Header\": {},\n          \"Params\": {}\n        }\n      },\n      \"filter\": null,\n      \"events\": {\n        \"baseEvent\": {\n          \"click\": null,\n          \"dblclick\": null,\n          \"mouseenter\": null,\n          \"mouseleave\": null\n        },\n        \"advancedEvents\": {\n          \"vnodeMounted\": null,\n          \"vnodeBeforeMount\": null\n        },\n        \"interactEvents\": []\n      },\n      \"key\": \"BarCrossrange\",\n      \"chartConfig\": {\n        \"key\": \"BarCrossrange\",\n        \"chartKey\": \"VBarCrossrange\",\n        \"conKey\": \"VCBarCrossrange\",\n        \"title\": \"横向柱状图\",\n        \"category\": \"Bars\",\n        \"categoryName\": \"柱状图\",\n        \"package\": \"Charts\",\n        \"chartFrame\": \"echarts\",\n        \"image\": \"bar_y.png\"\n      },\n      \"option\": {\n        \"legend\": {\n          \"show\": true,\n          \"type\": \"scroll\",\n          \"x\": \"center\",\n          \"y\": \"top\",\n          \"icon\": \"circle\",\n          \"orient\": \"horizontal\",\n          \"textStyle\": {\n            \"color\": \"#B9B8CE\",\n            \"fontSize\": 18\n          },\n          \"itemHeight\": 15,\n          \"itemWidth\": 15,\n          \"pageTextStyle\": {\n            \"color\": \"#B9B8CE\"\n          }\n        },\n        \"xAxis\": {\n          \"show\": true,\n          \"name\": \"\",\n          \"nameGap\": 15,\n          \"nameTextStyle\": {\n            \"color\": \"#B9B8CE\",\n            \"fontSize\": 12\n          },\n          \"inverse\": false,\n          \"axisLabel\": {\n            \"show\": true,\n            \"fontSize\": 12,\n            \"color\": \"#B9B8CE\",\n            \"rotate\": 0\n          },\n          \"position\": \"bottom\",\n          \"axisLine\": {\n            \"show\": true,\n            \"lineStyle\": {\n              \"color\": \"#B9B8CE\",\n              \"width\": 1\n            },\n            \"onZero\": true\n          },\n          \"axisTick\": {\n            \"show\": true,\n            \"length\": 5\n          },\n          \"splitLine\": {\n            \"show\": false,\n            \"lineStyle\": {\n              \"color\": \"#484753\",\n              \"width\": 1,\n              \"type\": \"solid\"\n            }\n          },\n          \"type\": \"value\"\n        },\n        \"yAxis\": {\n          \"show\": true,\n          \"name\": \"\",\n          \"nameGap\": 15,\n          \"nameTextStyle\": {\n            \"color\": \"#B9B8CE\",\n            \"fontSize\": 12\n          },\n          \"inverse\": false,\n          \"axisLabel\": {\n            \"show\": true,\n            \"fontSize\": 12,\n            \"color\": \"#B9B8CE\",\n            \"rotate\": 0\n          },\n          \"position\": \"left\",\n          \"axisLine\": {\n            \"show\": true,\n            \"lineStyle\": {\n              \"color\": \"#B9B8CE\",\n              \"width\": 1\n            },\n            \"onZero\": true\n          },\n          \"axisTick\": {\n            \"show\": true,\n            \"length\": 5\n          },\n          \"splitLine\": {\n            \"show\": true,\n            \"lineStyle\": {\n              \"color\": \"#484753\",\n              \"width\": 1,\n              \"type\": \"solid\"\n            }\n          },\n          \"type\": \"category\"\n        },\n        \"grid\": {\n          \"show\": false,\n          \"left\": \"10%\",\n          \"top\": \"60\",\n          \"right\": \"10%\",\n          \"bottom\": \"60\"\n        },\n        \"tooltip\": {\n          \"show\": true,\n          \"trigger\": \"axis\",\n          \"axisPointer\": {\n            \"show\": true,\n            \"type\": \"shadow\"\n          }\n        },\n        \"dataset\": {\n          \"dimensions\": [\n            \"product\",\n            \"data1\",\n            \"data2\"\n          ],\n          \"source\": [\n            {\n              \"product\": \"Mon\",\n              \"data1\": 120,\n              \"data2\": 130\n            },\n            {\n              \"product\": \"Tue\",\n              \"data1\": 200,\n              \"data2\": 130\n            },\n            {\n              \"product\": \"Wed\",\n              \"data1\": 150,\n              \"data2\": 312\n            },\n            {\n              \"product\": \"Thu\",\n              \"data1\": 80,\n              \"data2\": 268\n            },\n            {\n              \"product\": \"Fri\",\n              \"data1\": 70,\n              \"data2\": 155\n            },\n            {\n              \"product\": \"Sat\",\n              \"data1\": 110,\n              \"data2\": 117\n            },\n            {\n              \"product\": \"Sun\",\n              \"data1\": 130,\n              \"data2\": 160\n            }\n          ]\n        },\n        \"series\": [\n          {\n            \"type\": \"bar\",\n            \"barWidth\": null,\n            \"label\": {\n              \"show\": true,\n              \"position\": \"right\",\n              \"color\": \"#fff\",\n              \"fontSize\": 12\n            },\n            \"itemStyle\": {\n              \"color\": null,\n              \"borderRadius\": 0\n            }\n          },\n          {\n            \"type\": \"bar\",\n            \"barWidth\": null,\n            \"label\": {\n              \"show\": true,\n              \"position\": \"right\",\n              \"color\": \"#fff\",\n              \"fontSize\": 12\n            },\n            \"itemStyle\": {\n              \"color\": null,\n              \"borderRadius\": 0\n            }\n          }\n        ],\n        \"backgroundColor\": \"rgba(0,0,0,0)\"\n      }\n    }\n  ],\n  \"requestGlobalConfig\": {\n    \"requestDataPond\": [],\n    \"requestOriginUrl\": \"\",\n    \"requestInterval\": 30,\n    \"requestIntervalUnit\": \"second\",\n    \"requestParams\": {\n      \"Body\": {\n        \"form-data\": {},\n        \"x-www-form-urlencoded\": {},\n        \"json\": \"\",\n        \"xml\": \"\"\n      },\n      \"Header\": {},\n      \"Params\": {}\n    }\n  }\n}', 'data:image/png;base64,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', NULL, 1, '2026-06-13 15:48:48', '2026-06-14 12:34:28');

-- ----------------------------
-- Table structure for bi_python_datasource
-- ----------------------------
DROP TABLE IF EXISTS `bi_python_datasource`;
CREATE TABLE `bi_python_datasource`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `connector_id` bigint NOT NULL,
  `python_code` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `state` int NOT NULL,
  `endpoint_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `status` int NOT NULL,
  `remark` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `endpoint_key`(`endpoint_key` ASC) USING BTREE,
  INDEX `connector_id`(`connector_id` ASC) USING BTREE,
  CONSTRAINT `bi_python_datasource_ibfk_1` FOREIGN KEY (`connector_id`) REFERENCES `bi_db_connector` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of bi_python_datasource
-- ----------------------------

-- ----------------------------
-- Table structure for bi_python_datasource_version
-- ----------------------------
DROP TABLE IF EXISTS `bi_python_datasource_version`;
CREATE TABLE `bi_python_datasource_version`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `datasource_id` bigint NOT NULL,
  `version_no` int NOT NULL,
  `snapshot` json NOT NULL,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of bi_python_datasource_version
-- ----------------------------

-- ----------------------------
-- Table structure for bi_sql_datasource
-- ----------------------------
DROP TABLE IF EXISTS `bi_sql_datasource`;
CREATE TABLE `bi_sql_datasource`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `connector_id` bigint NOT NULL,
  `sql_content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `status` int NOT NULL,
  `remark` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `connector_id`(`connector_id` ASC) USING BTREE,
  CONSTRAINT `bi_sql_datasource_ibfk_1` FOREIGN KEY (`connector_id`) REFERENCES `bi_db_connector` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of bi_sql_datasource
-- ----------------------------

-- ----------------------------
-- Table structure for bi_sql_datasource_version
-- ----------------------------
DROP TABLE IF EXISTS `bi_sql_datasource_version`;
CREATE TABLE `bi_sql_datasource_version`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `datasource_id` bigint NOT NULL,
  `version_no` int NOT NULL,
  `snapshot` json NOT NULL,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of bi_sql_datasource_version
-- ----------------------------

-- ----------------------------
-- Table structure for sys_menu
-- ----------------------------
DROP TABLE IF EXISTS `sys_menu`;
CREATE TABLE `sys_menu`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `pid` bigint NOT NULL,
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `path` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `component` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `redirect` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `auth_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `status` int NOT NULL,
  `meta` json NULL,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 20304 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of sys_menu
-- ----------------------------
INSERT INTO `sys_menu` VALUES (1, 0, 'Dashboard', '/dashboard', NULL, '/workspace', 'catalog', NULL, 1, '{\"icon\": \"lucide:layout-dashboard\", \"order\": -1, \"title\": \"page.dashboard.title\"}', '2026-06-13 11:44:43');
INSERT INTO `sys_menu` VALUES (2, 0, 'System', '/system', NULL, NULL, 'catalog', NULL, 1, '{\"icon\": \"carbon:settings\", \"order\": 9997, \"title\": \"系统管理\"}', '2026-06-13 11:44:43');
INSERT INTO `sys_menu` VALUES (101, 1, 'Analytics', '/analytics', '/dashboard/analytics/index', NULL, 'menu', NULL, 0, '{\"icon\": \"lucide:area-chart\", \"title\": \"page.dashboard.analytics\", \"affixTab\": true, \"hideInMenu\": true}', '2026-06-13 11:44:43');
INSERT INTO `sys_menu` VALUES (102, 1, 'Workspace', '/workspace', '/dashboard/workspace/index', NULL, 'menu', NULL, 1, '{\"icon\": \"carbon:workspace\", \"title\": \"page.dashboard.workspace\"}', '2026-06-13 11:44:43');
INSERT INTO `sys_menu` VALUES (201, 2, 'SystemMenu', '/system/menu', '/system/menu/list', NULL, 'menu', 'System:Menu:List', 1, '{\"icon\": \"carbon:menu\", \"title\": \"菜单管理\"}', '2026-06-13 11:44:43');
INSERT INTO `sys_menu` VALUES (202, 2, 'SystemRole', '/system/role', '/system/role/list', NULL, 'menu', 'System:Role:List', 1, '{\"icon\": \"carbon:user-role\", \"title\": \"角色管理\"}', '2026-06-13 11:44:43');
INSERT INTO `sys_menu` VALUES (203, 2, 'SystemUser', '/system/user', '/system/user/list', NULL, 'menu', 'System:User:List', 1, '{\"icon\": \"carbon:user\", \"title\": \"用户管理\"}', '2026-06-13 11:44:43');
INSERT INTO `sys_menu` VALUES (3000, 0, 'BigScreenDesign', '/bi', NULL, '/bi/designer', 'catalog', NULL, 1, '{\"icon\": \"carbon:dashboard\", \"order\": 10, \"title\": \"大屏设计\"}', '2026-06-13 11:44:43');
INSERT INTO `sys_menu` VALUES (3001, 0, 'BiDatasource', '/datasource', NULL, '/datasource/db-connector', 'catalog', 'BI:Datasource:List', 1, '{\"icon\": \"carbon:data-base\", \"order\": 9, \"title\": \"数据源管理\"}', '2026-06-13 11:44:43');
INSERT INTO `sys_menu` VALUES (3002, 3000, 'BiDesigner', '/bi/designer', 'IFrameView', NULL, 'link', 'BI:Designer:Open', 1, '{\"icon\": \"carbon:chart-network\", \"link\": \"http://localhost:3020/index.html\", \"order\": 10, \"title\": \"大屏设计\", \"attachToken\": true, \"openInNewWindow\": true}', '2026-06-13 11:44:43');
INSERT INTO `sys_menu` VALUES (3003, 3001, 'BiDbConnector', '/datasource/db-connector', '/bi/datasource/db-connector/list', NULL, 'menu', 'BI:Datasource:DbConnector', 1, '{\"icon\": \"carbon:data-base-alt\", \"order\": 0, \"title\": \"DB连接器\"}', '2026-06-14 13:05:41');
INSERT INTO `sys_menu` VALUES (3004, 3001, 'BiHttpDatasource', '/datasource/http', '/bi/datasource/http-source/list', NULL, 'menu', 'BI:Datasource:Http', 1, '{\"icon\": \"carbon:api\", \"order\": 10, \"title\": \"HTTP数据源\"}', '2026-06-14 13:05:41');
INSERT INTO `sys_menu` VALUES (3005, 3001, 'BiSqlDatasource', '/datasource/sql', '/bi/datasource/sql-source/list', NULL, 'menu', 'BI:Datasource:Sql', 1, '{\"icon\": \"carbon:sql\", \"order\": 20, \"title\": \"SQL数据源\"}', '2026-06-14 13:05:41');
INSERT INTO `sys_menu` VALUES (3006, 3000, 'BiDatasourceLegacy', '/bi/datasource', NULL, '/datasource/db-connector', 'menu', NULL, 1, '{\"title\": \"数据源管理\", \"hideInMenu\": true, \"hideInBreadcrumb\": true}', '2026-06-14 13:05:41');
INSERT INTO `sys_menu` VALUES (3007, 3001, 'BiPythonDatasource', '/datasource/python', '/bi/datasource/python-source/list', NULL, 'menu', 'BI:Datasource:Python', 1, '{\"icon\": \"carbon:logo-python\", \"order\": 30, \"title\": \"Python数据源\"}', '2026-06-14 13:19:31');
INSERT INTO `sys_menu` VALUES (20101, 201, 'SystemMenuCreate', NULL, NULL, NULL, 'button', 'System:Menu:Create', 1, '{\"title\": \"新增\"}', '2026-06-13 11:44:43');
INSERT INTO `sys_menu` VALUES (20102, 201, 'SystemMenuEdit', NULL, NULL, NULL, 'button', 'System:Menu:Edit', 1, '{\"title\": \"修改\"}', '2026-06-13 11:44:43');
INSERT INTO `sys_menu` VALUES (20103, 201, 'SystemMenuDelete', NULL, NULL, NULL, 'button', 'System:Menu:Delete', 1, '{\"title\": \"删除\"}', '2026-06-13 11:44:43');
INSERT INTO `sys_menu` VALUES (20201, 202, 'SystemRoleCreate', NULL, NULL, NULL, 'button', 'System:Role:Create', 1, '{\"title\": \"新增\"}', '2026-06-13 11:44:43');
INSERT INTO `sys_menu` VALUES (20202, 202, 'SystemRoleEdit', NULL, NULL, NULL, 'button', 'System:Role:Edit', 1, '{\"title\": \"修改\"}', '2026-06-13 11:44:43');
INSERT INTO `sys_menu` VALUES (20203, 202, 'SystemRoleDelete', NULL, NULL, NULL, 'button', 'System:Role:Delete', 1, '{\"title\": \"删除\"}', '2026-06-13 11:44:43');
INSERT INTO `sys_menu` VALUES (20301, 203, 'SystemUserCreate', NULL, NULL, NULL, 'button', 'System:User:Create', 1, '{\"title\": \"新增\"}', '2026-06-13 11:44:43');
INSERT INTO `sys_menu` VALUES (20302, 203, 'SystemUserEdit', NULL, NULL, NULL, 'button', 'System:User:Edit', 1, '{\"title\": \"修改\"}', '2026-06-13 11:44:43');
INSERT INTO `sys_menu` VALUES (20303, 203, 'SystemUserDelete', NULL, NULL, NULL, 'button', 'System:User:Delete', 1, '{\"title\": \"删除\"}', '2026-06-13 11:44:43');

-- ----------------------------
-- Table structure for sys_role
-- ----------------------------
DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `status` int NOT NULL,
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `name`(`name` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of sys_role
-- ----------------------------
INSERT INTO `sys_role` VALUES (1, 'super', 1, '超级管理员，拥有全部权限', '2026-06-13 11:44:43');
INSERT INTO `sys_role` VALUES (2, 'admin', 1, '管理员', '2026-06-13 11:44:43');
INSERT INTO `sys_role` VALUES (3, 'user', 1, '普通用户', '2026-06-13 11:44:43');

-- ----------------------------
-- Table structure for sys_role_menu
-- ----------------------------
DROP TABLE IF EXISTS `sys_role_menu`;
CREATE TABLE `sys_role_menu`  (
  `role_id` bigint NOT NULL,
  `menu_id` bigint NOT NULL,
  PRIMARY KEY (`role_id`, `menu_id`) USING BTREE,
  INDEX `menu_id`(`menu_id` ASC) USING BTREE,
  CONSTRAINT `sys_role_menu_ibfk_1` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `sys_role_menu_ibfk_2` FOREIGN KEY (`menu_id`) REFERENCES `sys_menu` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of sys_role_menu
-- ----------------------------
INSERT INTO `sys_role_menu` VALUES (1, 1);
INSERT INTO `sys_role_menu` VALUES (2, 1);
INSERT INTO `sys_role_menu` VALUES (3, 1);
INSERT INTO `sys_role_menu` VALUES (1, 2);
INSERT INTO `sys_role_menu` VALUES (2, 2);
INSERT INTO `sys_role_menu` VALUES (1, 101);
INSERT INTO `sys_role_menu` VALUES (2, 101);
INSERT INTO `sys_role_menu` VALUES (3, 101);
INSERT INTO `sys_role_menu` VALUES (1, 102);
INSERT INTO `sys_role_menu` VALUES (2, 102);
INSERT INTO `sys_role_menu` VALUES (3, 102);
INSERT INTO `sys_role_menu` VALUES (1, 201);
INSERT INTO `sys_role_menu` VALUES (2, 201);
INSERT INTO `sys_role_menu` VALUES (1, 202);
INSERT INTO `sys_role_menu` VALUES (2, 202);
INSERT INTO `sys_role_menu` VALUES (1, 203);
INSERT INTO `sys_role_menu` VALUES (2, 203);
INSERT INTO `sys_role_menu` VALUES (1, 3000);
INSERT INTO `sys_role_menu` VALUES (2, 3000);
INSERT INTO `sys_role_menu` VALUES (1, 3001);
INSERT INTO `sys_role_menu` VALUES (2, 3001);
INSERT INTO `sys_role_menu` VALUES (1, 3002);
INSERT INTO `sys_role_menu` VALUES (2, 3002);
INSERT INTO `sys_role_menu` VALUES (1, 3003);
INSERT INTO `sys_role_menu` VALUES (2, 3003);
INSERT INTO `sys_role_menu` VALUES (1, 3004);
INSERT INTO `sys_role_menu` VALUES (2, 3004);
INSERT INTO `sys_role_menu` VALUES (1, 3005);
INSERT INTO `sys_role_menu` VALUES (2, 3005);
INSERT INTO `sys_role_menu` VALUES (1, 3006);
INSERT INTO `sys_role_menu` VALUES (2, 3006);
INSERT INTO `sys_role_menu` VALUES (1, 3007);
INSERT INTO `sys_role_menu` VALUES (2, 3007);
INSERT INTO `sys_role_menu` VALUES (1, 20101);
INSERT INTO `sys_role_menu` VALUES (2, 20101);
INSERT INTO `sys_role_menu` VALUES (1, 20102);
INSERT INTO `sys_role_menu` VALUES (2, 20102);
INSERT INTO `sys_role_menu` VALUES (1, 20103);
INSERT INTO `sys_role_menu` VALUES (1, 20201);
INSERT INTO `sys_role_menu` VALUES (2, 20201);
INSERT INTO `sys_role_menu` VALUES (1, 20202);
INSERT INTO `sys_role_menu` VALUES (2, 20202);
INSERT INTO `sys_role_menu` VALUES (1, 20203);
INSERT INTO `sys_role_menu` VALUES (2, 20203);
INSERT INTO `sys_role_menu` VALUES (1, 20301);
INSERT INTO `sys_role_menu` VALUES (2, 20301);
INSERT INTO `sys_role_menu` VALUES (1, 20302);
INSERT INTO `sys_role_menu` VALUES (2, 20302);
INSERT INTO `sys_role_menu` VALUES (1, 20303);
INSERT INTO `sys_role_menu` VALUES (2, 20303);

-- ----------------------------
-- Table structure for sys_user
-- ----------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `password` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `real_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `home_path` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `status` int NOT NULL,
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `ix_sys_user_username`(`username` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of sys_user
-- ----------------------------
INSERT INTO `sys_user` VALUES (1, 'admin', 'd45ed20a68062210536716fd582af629$151aa650c4849d97a69cf55fa334299114fb00a0464e3426bb631c54b264ea13', '超级管理员', '/workspace', 1, NULL, '2026-06-13 11:44:43');
INSERT INTO `sys_user` VALUES (2, 'jack', '6f22995639b14ad97f763dbad185bddf$1894d0214d5f238992c8dc381bf7905d58d73acd75c0a4c2f357d2edc133ebb5', 'Jack', '/analytics', 1, NULL, '2026-06-13 11:44:43');

-- ----------------------------
-- Table structure for sys_user_role
-- ----------------------------
DROP TABLE IF EXISTS `sys_user_role`;
CREATE TABLE `sys_user_role`  (
  `user_id` bigint NOT NULL,
  `role_id` bigint NOT NULL,
  PRIMARY KEY (`user_id`, `role_id`) USING BTREE,
  INDEX `role_id`(`role_id` ASC) USING BTREE,
  CONSTRAINT `sys_user_role_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `sys_user_role_ibfk_2` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of sys_user_role
-- ----------------------------
INSERT INTO `sys_user_role` VALUES (1, 1);
INSERT INTO `sys_user_role` VALUES (2, 3);

SET FOREIGN_KEY_CHECKS = 1;
