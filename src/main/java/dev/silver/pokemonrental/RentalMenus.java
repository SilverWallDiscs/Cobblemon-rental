package dev.silver.pokemonrental;

import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import java.util.*;
import java.util.function.IntConsumer;

/** Vanilla 6-row chest menus. ALL GUI slots are server-owned and cannot be extracted. */
public final class RentalMenus {
    private RentalMenus() { }
    public static void market(ServerPlayer p, RentalEngine e, int page) { new Session(p,e).market(page); }
    public static void setup(ServerPlayer p, RentalEngine e, int slot) { new Session(p,e).setup(slot); }
    public static void owned(ServerPlayer p, RentalEngine e, int page) { new Session(p,e).owned(page); }
    public static void rented(ServerPlayer p, RentalEngine e, int page) { new Session(p,e).rented(page); }

    private static final class LockedMenu extends AbstractContainerMenu {
        final SimpleContainer content = new SimpleContainer(54);
        private final ServerPlayer viewer;
        private final IntConsumer action;
        LockedMenu(int id, Inventory inventory, ServerPlayer player, IntConsumer action) {
            super(MenuType.GENERIC_9x6, id);
            this.viewer = player; this.action = action;
            // The vanilla GENERIC_9x6 client expects 54 GUI slots followed by 36
            // player inventory slots. Keep all 90 registered even though interaction
            // is disabled; omitting them causes client/server slot desynchronization.
            for (int y=0; y<6; y++) for (int x=0; x<9; x++)
                addSlot(new Slot(content, x+y*9, 8+x*18, 18+y*18) {
                    @Override public boolean mayPickup(Player user) { return false; }
                    @Override public boolean mayPlace(ItemStack item) { return false; }
                });
            for (int y=0; y<3; y++) for (int x=0; x<9; x++)
                addSlot(new Slot(inventory, x+(y+1)*9, 8+x*18, 140+y*18) {
                    @Override public boolean mayPickup(Player user) { return false; }
                    @Override public boolean mayPlace(ItemStack item) { return false; }
                });
            for (int x=0; x<9; x++)
                addSlot(new Slot(inventory, x, 8+x*18, 198) {
                    @Override public boolean mayPickup(Player user) { return false; }
                    @Override public boolean mayPlace(ItemStack item) { return false; }
                });
        }
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return player == viewer; }
        @Override public void clicked(int slot, int button, ClickType type, Player player) {
            if (player != viewer || viewer.containerMenu != this || slot < 0 || slot >= 54) return;
            if (type == ClickType.PICKUP && (button == 0 || button == 1)) action.accept(slot);
            // Deliberately never call super.clicked: prevents quick-move, number-key
            // swapping, pickup, throw, clone, drag, and inventory desync exploits.
        }
        void put(int slot, ItemStack item) { content.setItem(slot, item); broadcastChanges(); }
    }

    private static final class Session {
        final ServerPlayer player;
        final RentalEngine engine;
        LockedMenu menu;
        int currentPage, selectedSlot;
        long price = 1000;
        int minutes = 120;
        Session(ServerPlayer p, RentalEngine e) { player=p; engine=e; }
        void open(String title, IntConsumer callback) {
            player.openMenu(new SimpleMenuProvider((id, inv, ignored) -> {
                menu = new LockedMenu(id, inv, player, callback);
                return menu;
            }, Component.literal("§9Pokémon Rental §8| §f"+title)));
            filler();
        }
        void filler() { for(int i=0;i<54;i++) menu.put(i, icon(Items.GRAY_STAINED_GLASS_PANE," ")); }
        void show(int slot, Item item, String text, String... tooltip) { menu.put(slot, icon(item,text,tooltip)); }
        void back() { market(0); }
        void setup(int slot) {
            selectedSlot = slot; price=1000; minutes=120;
            drawSetup();
        }
        void drawSetup() {
            Pokemon pokemon = engine.partyPokemon(player, selectedSlot);
            if(pokemon == null || engine.isRentalPokemon(pokemon)) { market(0); return; }
            open("List slot "+selectedSlot, clicked -> {
                long[] delta = {-10000,-1000,-100,-1,1,100,1000,10000};
                int[] priceSlots = {18,19,20,21,23,24,25,26};
                for(int i=0;i<priceSlots.length;i++) if(clicked==priceSlots[i]) {
                    price = Math.max(1,Math.min(1_000_000_000_000L,price+delta[i])); drawSetup(); return;
                }
                if(clicked==28) { minutes=Math.max(120,minutes-1); drawSetup(); }
                else if(clicked==29) { minutes=Math.max(120,minutes-60); drawSetup(); }
                else if(clicked==30) { minutes=Math.max(120,minutes-15); drawSetup(); }
                else if(clicked==32) { minutes=Math.min(1440,minutes+15); drawSetup(); }
                else if(clicked==33) { minutes=Math.min(1440,minutes+60); drawSetup(); }
                else if(clicked==34) { minutes=Math.min(1440,minutes+1); drawSetup(); }
                else if(clicked==45) market(0);
                else if(clicked==49) { if(engine.list(player,selectedSlot,price,minutes)) owned(0); else drawSetup(); }
            });
            menu.put(13, pokemonIcon(pokemon, "§e"+pokemon.getSpecies().getName(),price,minutes));
            show(22, Items.GOLD_INGOT,"§6Price: §f"+price+" CD", "§7Change price with the buttons", "§7Min: 1 | Max: 1 trillion");
            show(31, Items.CLOCK,"§bDuration: §f"+duration(minutes),"§7Min: 2h | Max: 24h", "§7Adjust in 1, 15 or 60 minute steps");
            int[] priceSlots = {18,19,20,21,23,24,25,26};
            String[] names = {"-10,000","-1,000","-100","-1","+1","+100","+1,000","+10,000"};
            for(int i=0;i<priceSlots.length;i++) show(priceSlots[i],i<4?Items.RED_STAINED_GLASS_PANE:Items.LIME_STAINED_GLASS_PANE,"§e"+names[i]+" CD");
            show(28,Items.RED_STAINED_GLASS_PANE,"§c-1 minute");
            show(29,Items.RED_STAINED_GLASS_PANE,"§c-1 hour"); show(30,Items.RED_STAINED_GLASS_PANE,"§c-15 minutes");
            show(32,Items.LIME_STAINED_GLASS_PANE,"§a+15 minutes"); show(33,Items.LIME_STAINED_GLASS_PANE,"§a+1 hour");
            show(34,Items.LIME_STAINED_GLASS_PANE,"§a+1 minute");
            show(45,Items.ARROW,"§7Back");
            show(49,Items.EMERALD_BLOCK,"§aConfirm listing", "§7Your ORIGINAL goes into server escrow", "§7Any held item stays in escrow", "§7Your customer receives a temporary item-free copy");
        }
        void market(int page) {
            List<RentalListing> listings = engine.marketplace();
            int max = Math.max(0,(listings.size()-1)/45); currentPage=Math.max(0,Math.min(page,max));
            open("Marketplace "+(currentPage+1)+"/"+(max+1),clicked -> {
                if(clicked<45) { int index=currentPage*45+clicked; if(index<listings.size()) marketDetail(listings.get(index).id); }
                else if(clicked==45) market(currentPage-1);
                else if(clicked==48) rented(0);
                else if(clicked==49) owned(0);
                else if(clicked==53) market(currentPage+1);
            });
            for(int i=0;i<45;i++) { int at=i+currentPage*45; if(at>=listings.size()) break;
                RentalListing r=listings.get(at); menu.put(i, listingIcon(r)); }
            show(45,Items.ARROW,"§ePrevious page");
            show(48,Items.CLOCK,"§bMy active rentals");
            show(49,Items.CHEST,"§6My listings & earnings");
            show(53,Items.ARROW,"§eNext page");
        }
        void marketDetail(String id) {
            RentalListing r=engine.database().find(id);
            if(r==null || !r.available()) { market(0); return; }
            open("Rent " + r.species,clicked -> {
                if(clicked==45) market(0);
                if(clicked==49) { if(engine.rent(player,r.id)) rented(0); else market(0); }
            });
            menu.put(22,listingIcon(r));
            show(31,Items.GOLD_INGOT,"§e"+r.price+" CobbleDollars", "§7Duration: " + duration(r.minutes));
            show(45,Items.ARROW,"§7Back");
            if(!r.owns(player.getUUID())) show(49,Items.EMERALD_BLOCK,"§aPay and rent", "§cThis permanently charges " + r.price + " CD", "§7Temporary Pokémon will be recalled after expiry");
            else show(49,Items.BARRIER,"§cYou cannot rent your own Pokémon");
        }
        void owned(int page) {
            List<RentalListing> l=engine.byOwner(player.getUUID()); int max=Math.max(0,(l.size()-1)/45); currentPage=Math.max(0,Math.min(page,max));
            open("My listings "+(currentPage+1)+"/"+(max+1),clicked -> {
                if(clicked<45) { int at=currentPage*45+clicked; if(at<l.size()) ownerDetail(l.get(at).id); }
                else if(clicked==45) owned(currentPage-1);
                else if(clicked==47) { engine.claimAll(player); owned(currentPage); }
                else if(clicked==49) market(0);
                else if(clicked==53) owned(currentPage+1);
            });
            for(int i=0;i<45;i++) { int at=i+currentPage*45; if(at>=l.size()) break; menu.put(i,listingIcon(l.get(at))); }
            show(45,Items.ARROW,"§7Previous page"); show(47,Items.GOLD_BLOCK,"§6Claim all earnings", "§7Each completed purchase can be claimed once");
            show(49,Items.COMPASS,"§aMarketplace"); show(53,Items.ARROW,"§7Next page");
        }
        void ownerDetail(String id) {
            RentalListing r=engine.database().find(id);
            if(r==null || !r.owns(player.getUUID())) { owned(0); return; }
            open("My " + r.species,clicked -> {
                if(clicked==45) owned(0);
                else if(clicked==48) { engine.claimEarnings(player,r); ownerDetail(id); }
                else if(clicked==50) { engine.returnOriginal(player,id); ownerDetail(id); }
            });
            menu.put(22,listingIcon(r));
            show(31,Items.CLOCK,"§bStatus: " + r.state, "§7Time left: " + readable(engine.remaining(r)),
                "§7Original returned: "+r.originalReturned);
            show(45,Items.ARROW,"§7Back");
            show(48,Items.GOLD_INGOT,r.payoutClaimed ? "§7Earnings already claimed" : "§6Claim "+r.price+" CD", "§7Available after a successful purchase");
            if(r.state==RentalListing.State.LISTED) show(50,Items.CHEST,"§aCancel listing & return original");
            else if(r.state==RentalListing.State.EXPIRED) show(50,Items.CHEST,"§aClaim your original Pokémon");
            else show(50,Items.BARRIER,"§7Original is locked until rental expires");
        }
        void rented(int page) {
            List<RentalListing> l=engine.byRenter(player.getUUID()); int max=Math.max(0,(l.size()-1)/45); currentPage=Math.max(0,Math.min(page,max));
            open("My rentals "+(currentPage+1)+"/"+(max+1),clicked -> {
                if(clicked==45) rented(currentPage-1);
                else if(clicked==49) market(0);
                else if(clicked==53) rented(currentPage+1);
            });
            for(int i=0;i<45;i++) { int at=i+currentPage*45; if(at>=l.size()) break;
                RentalListing r=l.get(at); menu.put(i,listingIcon(r)); }
            show(45,Items.ARROW,"§7Previous page"); show(49,Items.COMPASS,"§aMarketplace"); show(53,Items.ARROW,"§7Next page");
        }
        ItemStack listingIcon(RentalListing r) {
            try {
                Pokemon source = engine.database().original(r,player.registryAccess());
                return pokemonIcon(source,"§b"+source.getSpecies().getName()+" §7(Lv."+r.level+")",r.price,r.minutes,
                    "§7Status: " + r.state,"§7Time left: " + readable(engine.remaining(r)),
                    "§7Owner: " + (r.ownerId.equals(player.getUUID())?"You":r.ownerId.toString()));
            } catch(Exception e) { return icon(Items.BARRIER,"§cEscrow unavailable", "§7Listing #"+r.id); }
        }
    }
    private static String duration(int minutes) { return (minutes/60)+"h "+(minutes%60)+"m"; }
    private static String readable(long ms) { return duration((int)((ms+59_999)/60_000)); }
    private static ItemStack icon(Item item,String title,String... lines) {
        ItemStack stack = new ItemStack(item); stack.set(DataComponents.CUSTOM_NAME,Component.literal(title));
        if(lines.length>0) stack.set(DataComponents.LORE,new ItemLore(Arrays.stream(lines).map(Component::literal).toList()));
        return stack;
    }
    private static ItemStack pokemonIcon(Pokemon pokemon, String display, long price, int minutes, String... extra) {
        ItemStack stack = PokemonItem.from(pokemon);
        stack.set(DataComponents.CUSTOM_NAME,Component.literal(display));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal("§7Level: §f"+pokemon.getLevel()));
        lore.add(Component.literal("§7Nature: §f"+pokemon.getNature().getName()));
        lore.add(Component.literal("§7Ability: §f"+pokemon.getAbility().getName()));
        lore.add(Component.literal("§7Gender: §f"+pokemon.getGender()));
        lore.add(Component.literal("§7Egg Groups: §f"+pokemon.getSpecies().getEggGroups()));
        lore.add(Component.literal("§eIVs §7(HP/ATK/DEF/SPA/SPD/SPE)"));
        lore.add(Component.literal("§f"+stats(pokemon,true)));
        lore.add(Component.literal("§bEVs §7(HP/ATK/DEF/SPA/SPD/SPE)"));
        lore.add(Component.literal("§f"+stats(pokemon,false)));
        lore.add(Component.literal("§6Price: §f"+price+" CobbleDollars"));
        lore.add(Component.literal("§bRental time: §f"+duration(minutes)));
        for(String line:extra) lore.add(Component.literal(line));
        stack.set(DataComponents.LORE,new ItemLore(lore)); return stack;
    }
    private static String stats(Pokemon p,boolean iv) {
        Stats[] s={Stats.HP,Stats.ATTACK,Stats.DEFENCE,Stats.SPECIAL_ATTACK,Stats.SPECIAL_DEFENCE,Stats.SPEED};
        List<String> out=new ArrayList<>(); for(Stats stat:s) {
            Integer value=iv?p.getIvs().get(stat):p.getEvs().get(stat); out.add(Integer.toString(value==null?0:value));
        }
        return String.join("/",out);
    }
}
