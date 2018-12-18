package org.aion.kernel;

import java.util.Arrays;
import org.aion.avm.core.util.Helpers;
import org.aion.vm.api.interfaces.Address;

/**
 * An account address.
 */
public class AvmAddress implements Address {
    private byte[] address;

    /**
     * Constructs a new address that consists of the specified underlying bytes.
     *
     * @param address the bytes of the address.
     */
    public AvmAddress(byte[] address) {
        if (address == null || address.length != SIZE) {
            throw new IllegalArgumentException("Address byte array must be exactly length " + SIZE);
        }
        this.address = address;
    }

    /**
     * Wraps the specified bytes into a new {@code AvmAddress} object.
     *
     * @param address The bytes to wrap.
     * @return The address.
     */
    public static AvmAddress wrap(byte[] address) {
        return new AvmAddress(address);
    }

    /**
     * @return The underlying bytes of this address.
     */
    @Override
    public byte[] toBytes() {
        return this.address;
    }

    /**
     * @return A string representation of this address.
     */
    @Override
    public String toString() {
        return Helpers.bytesToHexString(this.address);
    }

    /**
     * Returns {@code true} if, and only if, other is equal to this AvmAddress.
     *
     * @param other The object whose equality with this is to be tested.
     * @return True if this and other are equal.
     */
    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof AvmAddress)) {
            return false;
        }
        AvmAddress otherAsAddress = (AvmAddress) other;
        return Arrays.equals(this.address, otherAsAddress.address);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.address);
    }

}
