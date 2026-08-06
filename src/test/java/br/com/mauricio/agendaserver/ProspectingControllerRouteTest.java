package br.com.mauricio.agendaserver;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProspectingControllerRouteTest {
    @Test
    void exposesAuthenticatedSpecialtiesRoute() throws Exception {
        Class<ProspectingController> controller = ProspectingController.class;

        assertTrue(Modifier.isPublic(controller.getModifiers()),
                "ProspectingController deve ser público.");
        assertNotNull(controller.getAnnotation(RestController.class));

        RequestMapping base = controller.getAnnotation(RequestMapping.class);
        assertNotNull(base);
        assertArrayEquals(new String[]{"/api/agenda"}, base.value());

        Method method = controller.getDeclaredMethod(
                "activeSpecialties", String.class, String.class);
        assertTrue(Modifier.isPublic(method.getModifiers()),
                "O método de especialidades deve ser público.");

        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertNotNull(mapping);
        assertArrayEquals(new String[]{"/specialties"}, mapping.value());
    }
}
