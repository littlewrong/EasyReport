package cn.easyreport.combinesync.mapper;

import java.util.List;
import cn.easyreport.combinesync.domain.ErCombineSyncProgress;

public interface ErCombineSyncProgressMapper {
    List<ErCombineSyncProgress> selectByCombineId(Long combineId);

    int upsert(ErCombineSyncProgress progress);

    int deleteByCombineId(Long combineId);
}
