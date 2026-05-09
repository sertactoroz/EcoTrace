package com.ecotrace.api.waste.exception;

import com.ecotrace.api.common.error.BusinessException;
import com.ecotrace.api.common.error.ErrorCode;
import java.util.UUID;

public class WastePointNotFoundException extends BusinessException {
    public WastePointNotFoundException(UUID id) {
        super(ErrorCode.NOT_FOUND, "Waste point not found: " + id);
    }
}
