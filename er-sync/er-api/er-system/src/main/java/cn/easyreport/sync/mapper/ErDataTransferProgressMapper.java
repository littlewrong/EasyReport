package cn.easyreport.sync.mapper;

import java.util.List;
import cn.easyreport.sync.domain.ErDataTransferProgress;

public interface ErDataTransferProgressMapper {
    List<ErDataTransferProgress> selectByTransferId(Long transferId);

    int upsert(ErDataTransferProgress progress);
}
