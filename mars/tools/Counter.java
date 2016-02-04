package mars.tools;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Observable;

import mars.*;
import mars.mips.hardware.AddressErrorException;
import mars.mips.hardware.Coprocessor0;
import mars.mips.hardware.Memory;
import mars.mips.hardware.MemoryAccessNotice;

public class Counter extends AbstractMarsToolAndApplication {

	private static String heading = "Counter / Timer";
	private static String version = " Version 1.0";

	public static final int EXTERNAL_INTERRUPT_TIMER = 0x00000100;

	private static final int IN_ADDRESS_COUNTER_CTRL = Memory.memoryMapBaseAddress + 0x24;
	private static final int IN_ADDRESS_COUNTER = Memory.memoryMapBaseAddress + 0x28;
	private static final int OUT_ADDRESS_COUNTER = Memory.memoryMapBaseAddress + 0x2C;

	// Counter
	private static int CounterValueMax = 30;
	private static int CounterValue = CounterValueMax;
	private static boolean CounterInterruptOnOff = false;
	private static boolean CounterTrueSeconds = false;
	private static int CurrentMax = CounterValueMax;
	private static boolean enabled = false;

	private static long InitialMillis = 0;

	private static JLabel label_ctrl;
	private static JLabel label_limit;
	private static JLabel label_count;

	public Counter(String title, String heading) {
		super(title, heading);
	}

	public Counter() {
		super(heading + ", " + version, heading);
	}

	public static void main(String[] args) {
		new Counter(heading + ", " + version, heading).go();
	}

	public String getName() {
		return "Counter / Timer";
	}

	protected void addAsObserver() {
		addAsObserver(IN_ADDRESS_COUNTER_CTRL, IN_ADDRESS_COUNTER);
		addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress);
		addAsObserver(Memory.kernelTextBaseAddress, Memory.kernelTextLimitAddress);
	}

	protected void reset() {
		CounterValueMax = 30;
		CounterInterruptOnOff = false;
		CounterTrueSeconds = false;
		enabled = false;
	}

	public void update(Observable ressource, Object accessNotice) {
		MemoryAccessNotice notice = (MemoryAccessNotice) accessNotice;
		int address = notice.getAddress();

		if (notice.getAccessType() == MemoryAccessNotice.WRITE) {
			char value = (char) notice.getValue();
			int value_word = notice.getValue();

			if (address == IN_ADDRESS_COUNTER_CTRL) {
				CounterInterruptOnOff = ((value & 1) != 0);
				CounterTrueSeconds = ((value & 2) != 0);

				enabled = ((value & 4) != 0);

				if (enabled) {
					if (CounterTrueSeconds) {
						InitialMillis = System.currentTimeMillis();
						CurrentMax = CounterValueMax;
					} else {
						CounterValue = CounterValueMax;
					}
				}
			} else if (address == IN_ADDRESS_COUNTER) {
				CounterValueMax = value_word;
			}
		}

		if (enabled) {
			if (CounterTrueSeconds) {
				// Measure time using wall time clock
				long now = System.currentTimeMillis();

				long elapsed = now - InitialMillis;

				if (CurrentMax - elapsed > 0) {
					CounterValue = (int) (CurrentMax - elapsed);
				} else {
					InitialMillis = now;
					CounterValue = CounterValueMax;
					CurrentMax = CounterValueMax;

					if (CounterInterruptOnOff && (Coprocessor0.getValue(Coprocessor0.STATUS) & 2) == 0) {
						mars.simulator.Simulator.externalInterruptingDevice = /* Exceptions. */EXTERNAL_INTERRUPT_TIMER;
					}
				}

			} else {
				// Measure time by counting instructions
				if (CounterValue > 0) {
					CounterValue--;
				} else {
					CounterValue = CounterValueMax;
					if (CounterInterruptOnOff && (Coprocessor0.getValue(Coprocessor0.STATUS) & 2) == 0) {
						mars.simulator.Simulator.externalInterruptingDevice = /* Exceptions. */EXTERNAL_INTERRUPT_TIMER;
					}
				}
			}
		}

		// Always write out current count to OUT_ADDRESS_COUNTER
		if (!this.isBeingUsedAsAMarsTool || (this.isBeingUsedAsAMarsTool && connectButton.isConnected())) {
			synchronized (Globals.memoryAndRegistersLock) {
				try {
					Globals.memory.setRawWord(OUT_ADDRESS_COUNTER, CounterValue);
				} catch (AddressErrorException aee) {
					System.out.println("Tool author specified incorrect MMIO address!" + aee);
					System.exit(0);
				}
			}
			// HERE'S A HACK!! Want to immediately display the updated
			// memory value in MARS
			// but that code was not written for event-driven update
			// (e.g. Observer) --
			// it was written to poll the memory cells for their values.
			// So we force it to do so.

			if (Globals.getGui() != null
					&& Globals.getGui().getMainPane().getExecutePane().getTextSegmentWindow().getCodeHighlighting()) {
				Globals.getGui().getMainPane().getExecutePane().getDataSegmentWindow().updateValues();
			}
		}

		updateLabels();
	}

	private void updateLabels() {
		int control = 0;
		if (CounterInterruptOnOff)
			control += 1;
		if (CounterTrueSeconds)
			control += 2;
		if (enabled)
			control += 4;

		label_ctrl.setText(String.format("%8sb", Integer.toBinaryString(control)).replace(' ', '0'));
		label_limit.setText(String.format("%d", CounterValueMax));
		label_count.setText(String.format("%d", CounterValue));
	}

	/**
	 * Implementation of the inherited abstract method to build the main display
	 * area of the GUI. It will be placed in the CENTER area of a BorderLayout.
	 * The title is in the NORTH area, and the controls are in the SOUTH area.
	 */
	protected JComponent buildMainDisplayArea() {
		JPanel panelTools = new JPanel();
		panelTools.setLayout(new BoxLayout(panelTools, BoxLayout.PAGE_AXIS));

		panelTools.setPreferredSize(new Dimension(600, 300));

		JTextArea message = new JTextArea();
		message.setEditable(false);
		message.setLineWrap(true);
		message.setWrapStyleWord(true);
		message.setFont(new Font("Ariel", Font.PLAIN, 12));
		message.setText("This tool has two modes: Count instructions or Time \n\n"
				+ "The counter counts down to 0, then starts again from Count limit.\n"
				+ "When interruptions are enabled and the count reaches 0 an exception is started with cause register bit number 10 (mask: 0x00000100)\n\n"
				+ "Count mode will count the amount of instructions executed.\n"
				+ "Timer mode will measure time using the system clock.\n\n"
				+ "Count limit and Current count registers are in amount of instructions or in milliseconds, according to the mode set. \n\n"
				+ "Byte address 0xFFFF0024: \n" + "- bit 0 Enable interruptions \n"
				+ "- bit 1 Counter (bit off) / Timer (bit on) \n" + "- bit 2 Enable count \n\n"
				+ "Word address 0xFFFF0028 Count limit.\n\n" + "Word address 0xFFFF002C Current count.");
		message.setCaretPosition(0); // Assure first line is visible and at top
										// of scroll pane.
		JScrollPane panel = new JScrollPane(message);

		panelTools.add(panel);

		JPanel info = new JPanel(new GridLayout(3, 2));

		JLabel l1 = new JLabel("Control register:");
		info.add(l1);
		label_ctrl = new JLabel("");
		info.add(label_ctrl);

		JLabel l2 = new JLabel("Count limit register:");
		info.add(l2);
		label_limit = new JLabel("");
		info.add(label_limit);

		JLabel l3 = new JLabel("Current count register:");
		info.add(l3);
		label_count = new JLabel("");
		info.add(label_count);

		updateLabels();

		panelTools.add(info);

		return panelTools;
	}

}