package moe.mickey.spigot.pe;

import java.io.Externalizable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang.ArrayUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v1_7_R4.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import com.comphenix.protocol.wrappers.nbt.NbtCompound;
import com.comphenix.protocol.wrappers.nbt.NbtFactory;
import com.comphenix.protocol.wrappers.nbt.NbtWrapper;
import com.comphenix.protocol.wrappers.nbt.io.NbtBinarySerializer;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import net.minecraft.server.v1_7_R4.NBTTagCompound;
import net.minecraft.util.com.google.common.collect.Sets;

public class PrivilegeEscalation extends JavaPlugin implements Listener {
	
	public static class ItemCommand implements Externalizable {
		
		public static final NbtBinarySerializer SERIALIZER = new NbtBinarySerializer();
		
		public String name;
		public ItemStack itemStack;
		
		public int cd, clickType;
		public boolean onlyCommand;
		
		public List<String> commands = Lists.newArrayList();
		public boolean isConsumables;
		
		@Override
		public boolean equals(Object obj) {
			return obj instanceof ItemCommand && Objects.equals(name, ((ItemCommand) obj).name);
		}
		
		@Override
		public int hashCode() {
			return name == null ? 0 : name.hashCode();
		}

		@SuppressWarnings("deprecation")
		@Override
		public void writeExternal(ObjectOutput out) throws IOException {
			out.writeUTF(name);
			NBTTagCompound nmsNbt = new NBTTagCompound();
			CraftItemStack.asNMSCopy(itemStack).save(nmsNbt);
			SERIALIZER.serialize(NbtFactory.fromNMS(nmsNbt), out);
			out.writeInt(cd);
			out.writeInt(clickType);
			out.writeBoolean(onlyCommand);
			out.writeObject(commands);
			out.writeBoolean(isConsumables);
		}

		@SuppressWarnings("unchecked")
		@Override
		public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
			name = in.readUTF();
			NbtWrapper<NbtCompound> wrapper = SERIALIZER.deserialize(in);
			net.minecraft.server.v1_7_R4.ItemStack nmsItem = net.minecraft.server.v1_7_R4.ItemStack.createStack((NBTTagCompound) wrapper.getHandle());
			itemStack = CraftItemStack.asCraftMirror(nmsItem);
			cd = in.readInt();
			clickType = in.readInt();
			onlyCommand = in.readBoolean();
			commands = (List<String>) in.readObject();
			isConsumables = in.readBoolean();
		}
		
	}
	
	public static final Set<ItemCommand> COMMANDS = Sets.newHashSet();
	@SuppressWarnings("serial")
	public static final Map<String, Map<String, Integer>> CD_MAPPING = new HashMap<String, Map<String, Integer>>() {
		
		public Map<String,Integer> get(Object key) {
			if (key instanceof String) {
				Map<String, Integer> mapping = super.get(key);
				if (mapping == null)
					put((String) key, mapping = Maps.newHashMap());
				return mapping;
			}
			return null;
		}
		
	};
	
	@Override
	public void onEnable() {
		if (!getDataFolder().exists())
			getDataFolder().mkdirs();
		try {
			Arrays.stream(getDataFolder().listFiles()).filter(file -> file.getName().endsWith(".obj"))
			.filter(Objects::nonNull)
			.map(t -> {
				try {
					return new ObjectInputStream(new FileInputStream(t));
				} catch (IOException e) { e.printStackTrace(); }
				return null;
			})
			.filter(Objects::nonNull)
			.map(t -> {
				try {
					return t.readObject();
				} catch (IOException | ClassNotFoundException e) { e.printStackTrace(); }
				return null;
			})
			.filter(Objects::nonNull)
			.filter(ItemCommand.class::isInstance)
			.map(ItemCommand.class::cast)
			.forEach(COMMANDS::add);
		} catch (Exception e) { e.printStackTrace(); }
		getServer().getPluginManager().registerEvents(this, this);
	}
	
	@EventHandler
	public void onPlayerInteract(PlayerInteractEvent event) {
		ItemStack item = event.getPlayer().getItemInHand();
		if (item == null)
			return;
		ItemCommand result = null;
		for (ItemCommand command : COMMANDS)
			if (command.itemStack.isSimilar(item))
				result = command;
		if (result == null)
			return;
		if (result.clickType == 1 && !(event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK)) {
			if (result.onlyCommand)
				event.setCancelled(true);
			return;
		}
		if (result.clickType == 2 && !(event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
			if (result.onlyCommand)
				event.setCancelled(true);
			return;
		}
		event.setCancelled(true);
		Map<String, Integer> mapping = CD_MAPPING.get(event.getPlayer().getName());
		Integer lastUse = mapping.get(result.name);
		if (lastUse != null) {
			int cd = event.getPlayer().getTicksLived() - lastUse;
			if (cd < result.cd) {
				event.getPlayer().sendMessage("§c物品正在冷却中, 剩余: " + (result.cd - cd) / 20 + "s");
				return;
			}
		}
		mapping.put(result.name, event.getPlayer().getTicksLived());
		boolean isOp = event.getPlayer().isOp();
		if (!isOp)
			event.getPlayer().setOp(true);
		try {
			result.commands.forEach(command -> event.getPlayer().performCommand(command));
			if (result.isConsumables) {
				item.setAmount(item.getAmount() - 1);
				event.getPlayer().setItemInHand(item);
			}
		} catch (Exception e) { e.printStackTrace(); }
		if (!isOp)
			event.getPlayer().setOp(false);
	}
	
	public void saveItemCommand(ItemCommand itemCommand) {
		File outputFile = new File(getDataFolder(), itemCommand.name + ".obj");
		if (!outputFile.exists())
			try {
				outputFile.createNewFile();
			} catch (IOException e) { e.printStackTrace(); }
		try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(new FileOutputStream(outputFile))) {
			objectOutputStream.writeObject(itemCommand);
		} catch (IOException e) { e.printStackTrace(); }
	}
	
	public void delItemCommand(ItemCommand itemCommand) {
		File outputFile = new File(getDataFolder(), itemCommand.name + ".obj");
		if (outputFile.exists())
			outputFile.delete();
		COMMANDS.remove(itemCommand);
	}
	
	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (label.equalsIgnoreCase("pe")) {
			if (args.length > 0) {
				if (args[0].equalsIgnoreCase("help")) {
					sender.sendMessage("§a[ PrivilegeEscalation ] (正式版 v1.7.3.48.20170722, By: Sayaka & Kyouko)");
					sender.sendMessage("§a----------------------------------------------------------------------------------------------------------");
					sender.sendMessage("§a[显示所有] /pe all");
					sender.sendMessage("§7例: /pe all");
					sender.sendMessage("§a[标记物品] /pe mark <标记名(唯一标识)> <是否消耗(true或者false)> <仅作为命令物品(true或者false)> <冷却时间(整数)>"
							+ " <触发按键(1-> 左键, 2 -> 右键, 其它整数 -> 任意)>");
					sender.sendMessage("§7例: /pe mark sayaka fasle true 40 1");
					sender.sendMessage("§a[添加命令] /pe add <标记名(唯一标识)> <绑定的命令(可出现空格)>");
					sender.sendMessage("§7例: /pe add sayaka tp 0 120 0");
					sender.sendMessage("§a[清空命令] /pe clean <标记名(唯一标识)>");
					sender.sendMessage("§7例: /pe clean sayaka");
					sender.sendMessage("§a[清除标记] /pe del <标记名(唯一标识)>");
					sender.sendMessage("§7例: /pe del sayaka");
					sender.sendMessage("§a[给予玩家] /pe give <玩家名> <标记名(唯一标识)> [数量]");
					sender.sendMessage("§7例: /pe give kyouko sayaka 64");
					sender.sendMessage("§a----------------------------------------------------------------------------------------------------------");
					return true;
				}
				if (args[0].equalsIgnoreCase("all")) {
					if (sender.isOp()) {
						int count[] = {0};
						sender.sendMessage("§a共有" + COMMANDS.size() + "个标记");
						COMMANDS.forEach(otherItemCommand -> {
							sender.sendMessage("§a" + count[0]++ + "> [" + otherItemCommand.name + "], 物品信息: "
									+ CraftItemStack.asNMSCopy(otherItemCommand.itemStack) + ", 是否消耗: " + otherItemCommand.isConsumables
									+ ", 是否仅触发命令: " + otherItemCommand.onlyCommand + ", 冷却时间: " + otherItemCommand.cd + "tick, 触发按键: "
									+ (otherItemCommand.clickType == 1 ? "左键" : otherItemCommand.clickType == 2 ? "右键" : "任意"));
							otherItemCommand.commands.forEach(otherCommand -> sender.sendMessage("§a[/" + otherCommand + "]"));
						});
					} else
						sender.sendMessage("§c请由OP调用该命令");
					return true;
				}
				if (args[0].equalsIgnoreCase("mark")) {
					if (sender.isOp()) {
						if (sender instanceof Player) {
							Player player = (Player) sender;
							if (args.length > 3) {
								ItemStack item = player.getItemInHand();
								if (item != null) {
									boolean isConsumables;
									try {
										isConsumables = Boolean.valueOf(args[2]);
									} catch (Exception e) {
										sender.sendMessage("§c无法将[" + args[2] + "]解析为布尔型");
										return false;
									}
									boolean onlyCommand;
									try {
										onlyCommand = Boolean.valueOf(args[3]);
									} catch (Exception e) {
										sender.sendMessage("§c无法将[" + args[3] + "]解析为布尔型");
										return false;
									}
									int cd;
									try {
										cd = Integer.valueOf(args[4]);
									} catch (Exception e) {
										sender.sendMessage("§c无法将[" + args[4] + "]解析为整型");
										return false;
									}
									int clickType;
									try {
										clickType = Integer.valueOf(args[5]);
									} catch (Exception e) {
										sender.sendMessage("§c无法将[" + args[5] + "]解析为整型");
										return false;
									}
									ItemCommand itemCommand = new ItemCommand();
									itemCommand.name = args[1];
									itemCommand.itemStack = item.clone();
									NbtCompound compound = (NbtCompound) NbtFactory.fromItemTag(itemCommand.itemStack);
									compound.put("itemCommand", itemCommand.name);
									itemCommand.isConsumables = isConsumables;
									itemCommand.onlyCommand = onlyCommand;
									itemCommand.cd = cd;
									itemCommand.clickType = clickType;
									player.setItemInHand(new ItemStack(Material.AIR));
									player.setItemInHand(itemCommand.itemStack.clone());
									COMMANDS.remove(itemCommand);
									COMMANDS.add(itemCommand);
									saveItemCommand(itemCommand);
									sender.sendMessage("§a成功标记[" + itemCommand.name + "], 物品信息: "
									+ CraftItemStack.asNMSCopy(itemCommand.itemStack) + ", 是否消耗: " + isConsumables
											+ ", 是否仅触发命令: " + onlyCommand + ", 冷却时间: " + cd + "tick, 触发按键: " + (clickType == 1 ? "左键" :
												clickType == 2 ? "右键" : "任意"));
								} else
									sender.sendMessage("§c请手持目标物品调用该命令");
							} else
								sender.sendMessage("§c缺少需要的参数");
						} else
							sender.sendMessage("§c请由玩家调用该命令");
					} else
						sender.sendMessage("§c请由OP调用该命令");
					return true;
				}
				if (args[0].equalsIgnoreCase("add")) {
					if (sender.isOp()) {
						if (args.length > 2) {
							String name = args[1];
							ItemCommand result = null;
							for (ItemCommand itemCommand : COMMANDS)
								if (itemCommand.name.equals(name))
									result = itemCommand;
							if (result != null) {
								String newCommand = Joiner.on(' ').join(ArrayUtils.subarray(args, 2, args.length));
								result.commands.add(newCommand);
								saveItemCommand(result);
								sender.sendMessage("§a成功给[" + result.name + "]添加命令[/" + newCommand + "]");
								sender.sendMessage("§a当前所有命令: ");
								result.commands.forEach(otherCommand -> sender.sendMessage("§a[/" + otherCommand + "]"));
							} else
								sender.sendMessage("§c指定的物品不存在");
						} else
							sender.sendMessage("§c缺少需要的参数");
					} else
						sender.sendMessage("§c请由OP调用该命令");
					return true;
				}
				if (args[0].equalsIgnoreCase("clean")) {
					if (sender.isOp()) {
						if (args.length > 1) {
							String name = args[1];
							ItemCommand result = null;
							for (ItemCommand itemCommand : COMMANDS)
								if (itemCommand.name.equals(name))
									result = itemCommand;
							if (result != null) {
								result.commands.clear();
								saveItemCommand(result);
								sender.sendMessage("§a成功清空[" + name + "]的所有指令");
							} else
								sender.sendMessage("§c指定的物品不存在");
						} else
							sender.sendMessage("§c缺少需要的参数");
					} else
						sender.sendMessage("§c请由OP调用该命令");
					return true;
				}
				if (args[0].equalsIgnoreCase("del")) {
					if (sender.isOp()) {
						if (args.length > 1) {
							String name = args[1];
							ItemCommand result = null;
							for (ItemCommand itemCommand : COMMANDS)
								if (itemCommand.name.equals(name))
									result = itemCommand;
							if (result != null) {
								delItemCommand(result);
								sender.sendMessage("§a成功删除[" + name + "]");
							} else
								sender.sendMessage("§c指定的物品不存在");
						} else
							sender.sendMessage("§c缺少需要的参数");
					} else
						sender.sendMessage("§c请由OP调用该命令");
					return true;
				}
				if (args[0].equalsIgnoreCase("give")) {
					if (sender.isOp()) {
						if (args.length > 2) {
							int count;
							try {
								count = args.length > 3 ? Integer.valueOf(args[3]) : 1;
							} catch (Exception e) {
								sender.sendMessage("§c无法将[" + args[3] + "]解析为整型");
								return false;
							}
							Player player = Bukkit.getPlayer(args[1]);
							if (player != null) {
								String name = args[2];
								ItemCommand result = null;
								for (ItemCommand itemCommand : COMMANDS)
									if (itemCommand.name.equals(name))
										result = itemCommand;
								if (result != null) {
									ItemStack item = result.itemStack.clone();
									item.setAmount(count);
									Map<Integer, ItemStack> mapping = player.getInventory().addItem(item);
									mapping.values().forEach(drop -> player.getWorld().dropItem(player.getLocation(), drop));
									sender.sendMessage("§a成功将[" + result.name + "]给予玩家[" + player.getName() + "]");
								} else
									sender.sendMessage("§c指定的物品不存在");
							} else
								sender.sendMessage("§c指定的玩家不存在");
						} else
							sender.sendMessage("§c缺少需要的参数");
					} else
						sender.sendMessage("§c请由OP调用该命令");
					return true;
				}
			}
			sender.sendMessage("§c未知的子命令");
		}
		return false;
	}

}
