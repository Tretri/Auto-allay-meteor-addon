package com.example.allayaddon;

import com.example.allayaddon.modules.AllayAutoGiver;
import com.example.allayaddon.modules.AllayBreeder;
import com.example.allayaddon.modules.AllayKillAura;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AllayAddon extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger("AllayAddon");
    public static final Category CATEGORY = new Category("Tretri's Allay");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Allay addon");

        Modules.get().add(new AllayBreeder());
        Modules.get().add(new AllayKillAura());
        Modules.get().add(new AllayAutoGiver());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    /** Кореневий пакет аддону — Meteor використовує його для реєстрації @EventHandler. */
    @Override
    public String getPackage() {
        return "com.example.allayaddon";
    }
}
