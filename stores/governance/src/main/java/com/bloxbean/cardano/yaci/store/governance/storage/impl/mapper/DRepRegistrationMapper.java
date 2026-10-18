package com.bloxbean.cardano.yaci.store.governance.storage.impl.mapper;

import com.bloxbean.cardano.yaci.store.governance.domain.DRepRegistration;
import com.bloxbean.cardano.yaci.store.governance.storage.impl.model.DRepRegistrationEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public abstract class DRepRegistrationMapper {
    public abstract DRepRegistrationEntity toDRepRegistrationEntity(DRepRegistration drepRegistration);

    @Mapping(target = "anchorMetadata", ignore = true)
    public abstract DRepRegistration toDRepRegistration(DRepRegistrationEntity drepRegistration);
}
