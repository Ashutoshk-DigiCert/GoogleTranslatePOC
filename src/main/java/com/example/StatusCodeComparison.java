package com.example;

import com.google.api.gax.rpc.StatusCode;
import com.google.rpc.Code;

public class StatusCodeComparison {

    public static void main(String[] args) {
        StatusCode.Code gaxCode = StatusCode.Code.OK;
        Code rpcCode = Code.OK;

        // Compare using integer values or ordinals
        if (gaxCode.ordinal() == rpcCode.getNumber()) {
            System.out.println("The status codes are equal.");
        } else {
            System.out.println("The status codes are different.");
        }
    }
}
