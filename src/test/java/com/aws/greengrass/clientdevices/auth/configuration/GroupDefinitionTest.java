/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.clientdevices.auth.configuration;

import com.aws.greengrass.clientdevices.auth.session.Session;
import com.aws.greengrass.clientdevices.auth.session.SessionImpl;
import com.aws.greengrass.clientdevices.auth.session.attribute.DeviceAttribute;
import com.aws.greengrass.clientdevices.auth.session.attribute.WildcardSuffixAttribute;
import com.aws.greengrass.clientdevices.auth.configuration.parser.ParseException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class GroupDefinitionTest {

    @Test
    void GIVEN_groupDefinitionAndMatchingSession_WHEN_containsSession_THEN_returnsTrue() throws ParseException {
        GroupDefinition groupDefinition = new GroupDefinition("thingName: thing", "Policy1");
        Session session = Mockito.mock(Session.class);
        DeviceAttribute attribute = new WildcardSuffixAttribute("thing");
        Mockito.when(session.getSessionAttribute(any(), any())).thenReturn(attribute);
        assertThat(groupDefinition.containsClientDevice(session), is(true));
    }

    @Test
    void GIVEN_groupDefinitionWithWildcardAndMatchingSession_WHEN_containsSession_THEN_returnsTrue()
            throws ParseException {
        GroupDefinition groupDefinition = new GroupDefinition("thingName: thing*", "Policy1");
        Session session = Mockito.mock(Session.class);
        DeviceAttribute attribute = new WildcardSuffixAttribute("thing-A");
        Mockito.when(session.getSessionAttribute(any(), any())).thenReturn(attribute);
        assertThat(groupDefinition.containsClientDevice(session), is(true));
    }

    @Test
    void GIVEN_groupDefinitionAndNonMatchingSession_WHEN_containsSession_THEN_returnsFalse() throws ParseException {
        GroupDefinition groupDefinition = new GroupDefinition("thingName: thing", "Policy1");
        assertThat(groupDefinition.containsClientDevice(new SessionImpl()), is(false));
    }
}
