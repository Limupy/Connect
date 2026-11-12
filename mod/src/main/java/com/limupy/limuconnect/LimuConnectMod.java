package com.limupy.limuconnect;

import net.neoforged.fml.common.Mod;

@Mod(LimuConnectMod.MOD_ID)
public final class LimuConnectMod {
    public static final String MOD_ID = "limuconnect";

    public LimuConnectMod() {
        // Client UI is registered separately through a Dist.CLIENT event subscriber.
    }
}
