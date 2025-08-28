package com.inubot.script.herbcleaner.task;

import com.google.inject.Inject;
import com.inubot.script.herbcleaner.Domain;
import com.inubot.script.herbcleaner.data.Herb;
import org.rspeer.commons.logging.Log;
import org.rspeer.game.Game;
import org.rspeer.game.adapter.component.StockMarketable;
import org.rspeer.game.adapter.component.inventory.Bank;
import org.rspeer.game.adapter.component.inventory.Inventory;
import org.rspeer.game.component.Interfaces;
import org.rspeer.game.component.InventoryType;
import org.rspeer.game.config.item.entry.builder.ItemEntryBuilder;
import org.rspeer.game.config.item.loadout.BackpackLoadout;
import org.rspeer.game.script.Task;
import org.rspeer.game.script.TaskDescriptor;
import org.rspeer.game.service.inventory.InventoryCache;
import org.rspeer.game.service.stockmarket.StockMarketEntry;
import org.rspeer.game.service.stockmarket.StockMarketService;

import java.util.Comparator;
import java.util.function.Predicate;

@TaskDescriptor(name = "Banking")
public class BankTask extends Task {

  private final Domain domain;
  private final InventoryCache inventory;
  private final StockMarketService service;

  @Inject
  public BankTask(Domain domain, InventoryCache inventory, StockMarketService service) {
    this.domain = domain;
    this.inventory = inventory;
    this.service = service;
  }

  @Override
  public boolean execute() {
    //only bank if forceBank is true or we have no herbs
    if (!domain.isForceBank() && !Inventory.backpack().query().ids(Herb.GRIMY_IDS).results().isEmpty()) {
      return false;
    }

    if (!Bank.isOpen()) {
      sleepUntil(Bank::isOpen, 3);
      return Bank.open();
    }

    BackpackLoadout loadout = new BackpackLoadout("Herbs");
    Herb herb = getBestHerb(x -> !inventory.query(InventoryType.BANK).ids(x.getGrimyId()).stackSize(26).results().isEmpty());
    if (herb == null) {
      herb = getBestHerb(x -> domain.ticksSince(x.getLastBuyTick()) > 24000); //~4 hours buy limit
    }

    if (herb == null) {
      Log.info("Out of herbs to sustain 4 hour buy limits");
      domain.setStopping(true);
      return false;
    }

    Log.info("Hahahah!" + herb.getName(false));
    loadout.add(new ItemEntryBuilder()
        .key(herb.getName(false))
        .quantity(26)
        .build());

    loadout.setOutOfItemListener(entry -> {
      Herb next = getBestHerb(x -> domain.ticksSince(x.getLastBuyTick()) > 24000);
      if (next == null) {
        Log.info("Out of herbs to sustain 4 hour buy limits");
        domain.setStopping(true);
        return;
      }

      if (Game.getAccountType().isIronman()) {
        Log.info("Sucks to be you LOL");
        domain.setStopping(true);
        return;
      }

      //-2 means it'll press +5% twice, change to -1 or -3 if you want to buy slwoer/faster
      service.submit(StockMarketable.Type.BUY, new StockMarketEntry(next.getGrimyId(), 11000, -2));
      //TODO submit selling here so it does it all together
    });

    if (loadout.withdraw(Inventory.bank())) {
      Interfaces.closeSubs();
      domain.setForceBank(false);
      sleep(3);
    }

    return true;
  }

  private Herb getBestHerb(Predicate<Herb> predicate) {
    return Herb.getCleanable(domain.getIgnoreLevel())
        .stream()
        .sorted(Comparator.comparingInt(Herb::getLevel).reversed())
        .filter(predicate)
        .findFirst()
        .orElse(null);
  }
}
