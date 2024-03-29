package qupath.ext.omero.gui.browser.serverbrowser;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.omero.gui.browser.serverbrowser.hierarchy.HierarchyCellFactory;
import qupath.ext.omero.gui.browser.serverbrowser.hierarchy.HierarchyItem;
import qupath.ext.omero.gui.browser.serverbrowser.settings.Settings;
import qupath.fx.controls.PredicateTextField;
import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
import qupath.ext.omero.gui.browser.serverbrowser.advancedsearch.AdvancedSearch;
import qupath.ext.omero.core.WebClient;
import qupath.ext.omero.gui.browser.serverbrowser.advancedinformation.AdvancedInformation;
import qupath.ext.omero.core.entities.permissions.Group;
import qupath.ext.omero.core.entities.permissions.Owner;
import qupath.ext.omero.core.entities.repositoryentities.RepositoryEntity;
import qupath.ext.omero.core.entities.repositoryentities.serverentities.ServerEntity;
import qupath.ext.omero.core.entities.repositoryentities.serverentities.image.Image;
import qupath.ext.omero.core.entities.repositoryentities.OrphanedFolder;
import qupath.ext.omero.gui.UiUtilities;
import qupath.ext.omero.core.pixelapis.PixelAPI;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * <p>
 *     Window allowing the user to browse an OMERO server,
 *     get information about OMERO entities and open OMERO images.
 * </p>
 * <p>
 *     It displays a hierarchy of OMERO entities using classes of
 *     {@link qupath.ext.omero.gui.browser.serverbrowser.hierarchy hierarchy}.
 * </p>
 * <p>
 *     It can launch a window showing details on an OMERO entity, described in
 *     {@link qupath.ext.omero.gui.browser.serverbrowser.advancedinformation advanced_information}.
 * </p>
 * <p>
 *     It can launch a window that performs a search on OMERO entities, described in
 *     {@link qupath.ext.omero.gui.browser.serverbrowser.advancedsearch advanced_search}.
 * </p>
 * <p>
 *     It uses a {@link BrowserModel} to update its state.
 * </p>
 */
public class Browser extends Stage {

    private static final Logger logger = LoggerFactory.getLogger(Browser.class);
    private static final float DESCRIPTION_ATTRIBUTE_PROPORTION = 0.25f;
    private static final ResourceBundle resources = UiUtilities.getResources();
    private final WebClient client;
    private final BrowserModel browserModel;
    @FXML
    private Label serverHost;
    @FXML
    private Label username;
    @FXML
    private Label numberOpenImages;
    @FXML
    private Label rawPixelAccess;
    @FXML
    private ComboBox<PixelAPI> pixelAPI;
    @FXML
    private Label loadingObjects;
    @FXML
    private Label loadingOrphaned;
    @FXML
    private Label loadingThumbnail;
    @FXML
    private MenuButton groupOwner;
    @FXML
    private TreeView<RepositoryEntity> hierarchy;
    @FXML
    private MenuItem moreInfo;
    @FXML
    private MenuItem openBrowser;
    @FXML
    private MenuItem copyToClipboard;
    @FXML
    private MenuItem collapseAllItems;
    @FXML
    private HBox filterContainer;
    @FXML
    private Button importImage;
    @FXML
    private Canvas canvas;
    @FXML
    private TableView<Integer> description;
    @FXML
    private TableColumn<Integer, String> attributeColumn;
    @FXML
    private TableColumn<Integer, String> valueColumn;
    private AdvancedSearch advancedSearch = null;
    private Settings settings = null;

    /**
     * Create the browser window.
     *
     * @param client  the web client which will be used by this browser to retrieve data from the corresponding OMERO server
     * @throws IOException if an error occurs while creating the browser
     */
    public Browser(WebClient client) throws IOException {
        this.client = client;
        this.browserModel = new BrowserModel(client);

        UiUtilities.loadFXML(this, Browser.class.getResource("browser.fxml"));

        initUI();
        setUpListeners();
    }

    @FXML
    private void onSettingsClicked(ActionEvent ignoredEvent) {
        if (settings == null) {
            try {
                settings = new Settings(this, client);
            } catch (IOException e) {
                logger.error("Error while creating the settings window", e);
            }
        } else {
            UiUtilities.showWindow(settings);
        }
    }

    @FXML
    private void onImagesTreeClicked(MouseEvent event) {
        if (event.getClickCount() == 2) {
            var selectedItem = hierarchy.getSelectionModel().getSelectedItem();
            RepositoryEntity selectedObject = selectedItem == null ? null : selectedItem.getValue();

            if (selectedObject instanceof Image image && image.isSupported().get()) {
                UiUtilities.openImages(client.getApisHandler().getItemURI(image));
            }
        }
    }

    @FXML
    private void onMoreInformationMenuClicked(ActionEvent ignoredEvent) {
        var selectedItem = hierarchy.getSelectionModel().getSelectedItem();
        if (selectedItem != null && selectedItem.getValue() instanceof ServerEntity serverEntity) {
            client.getApisHandler().getAnnotations(serverEntity.getId(), serverEntity.getClass()).thenAccept(annotations -> Platform.runLater(() -> {
                if (annotations.isPresent()) {
                    try {
                        new AdvancedInformation(this, serverEntity, annotations.get());
                    } catch (IOException e) {
                        logger.error("Error while creating the advanced information window", e);
                    }
                } else {
                    Dialogs.showErrorMessage(
                            resources.getString("Browser.ServerBrowser.cantDisplayInformation"),
                            MessageFormat.format(resources.getString("Browser.ServerBrowser.errorWhenFetchingInformation"), serverEntity.getLabel().get())
                    );
                }
            }));
        }
    }

    @FXML
    private void onOpenInBrowserMenuClicked(ActionEvent ignoredEvent) {
        var selectedItem = hierarchy.getSelectionModel().getSelectedItem();
        if (selectedItem != null && selectedItem.getValue() instanceof ServerEntity serverEntity) {
            QuPathGUI.openInBrowser(client.getApisHandler().getItemURI(serverEntity));
        }
    }

    @FXML
    private void onCopyToClipboardMenuClicked(ActionEvent ignoredEvent) {
        List<String> URIs = hierarchy.getSelectionModel().getSelectedItems().stream()
                .map(item -> {
                    if (item.getValue() instanceof ServerEntity serverEntity) {
                        return client.getApisHandler().getItemURI(serverEntity);
                    } else {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();

        if (!URIs.isEmpty()) {
            ClipboardContent content = new ClipboardContent();
            if (URIs.size() == 1) {
                content.putString(URIs.get(0));
            } else {
                content.putString("[" + String.join(", ", URIs) + "]");
            }
            Clipboard.getSystemClipboard().setContent(content);

            Dialogs.showInfoNotification(
                    resources.getString("Browser.ServerBrowser.copyURIToClipboard"),
                    resources.getString("Browser.ServerBrowser.uriSuccessfullyCopied")
            );
        } else {
            Dialogs.showWarningNotification(
                    resources.getString("Browser.ServerBrowser.copyURIToClipboard"),
                    resources.getString("Browser.ServerBrowser.itemNeedsSelected")
            );
        }
    }

    @FXML
    private void onCollapseAllItemsMenuClicked(ActionEvent ignoredEvent) {
        collapseTreeView(hierarchy.getRoot());
    }

    @FXML
    private void onAdvancedClicked(ActionEvent ignoredEvent) {
        if (advancedSearch == null) {
            try {
                advancedSearch = new AdvancedSearch(this, client);
            } catch (IOException e) {
                logger.error("Error while creating the settings window", e);
            }
        } else {
            UiUtilities.showWindow(advancedSearch);
        }
    }

    @FXML
    private void onImportButtonClicked(ActionEvent ignoredEvent) {
        UiUtilities.openImages(
                hierarchy.getSelectionModel().getSelectedItems().stream()
                        .map(TreeItem::getValue)
                        .map(repositoryEntity -> {
                            if (repositoryEntity instanceof ServerEntity serverEntity) {
                                return serverEntity;
                            } else {
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .map(serverEntity ->
                                client.getApisHandler().getItemURI(serverEntity)
                        )
                        .toArray(String[]::new)
        );
    }

    private void initUI() {
        serverHost.setText(client.getApisHandler().getWebServerURI().getHost());

        if (browserModel.getSelectedPixelAPI().get() != null && browserModel.getSelectedPixelAPI().get().canAccessRawPixels()) {
            rawPixelAccess.setText(resources.getString("Browser.ServerBrowser.accessRawPixels"));
            rawPixelAccess.setGraphic(UiUtilities.createStateNode(true));
        } else {
            rawPixelAccess.setText(resources.getString("Browser.ServerBrowser.noAccessRawPixels"));
            rawPixelAccess.setGraphic(UiUtilities.createStateNode(false));
        }

        pixelAPI.setItems(browserModel.getAvailablePixelAPIs());
        pixelAPI.setConverter(new StringConverter<>() {
            @Override
            public String toString(PixelAPI pixelAPI) {
                return pixelAPI == null ? "" : pixelAPI.getName();
            }
            @Override
            public PixelAPI fromString(String string) {
                return null;
            }
        });
        pixelAPI.getSelectionModel().select(browserModel.getSelectedPixelAPI().get());

        groupOwner.getItems().addAll(client.getServer().getGroups().stream()
                .map(group -> {
                    List<Owner> owners = group.equals(Group.getAllGroupsGroup()) ?
                            client.getServer().getOwners() :
                            group.getOwners();

                    if (!owners.isEmpty()) {
                        Menu menu = new Menu(group.getName());
                        menu.getItems().addAll(
                                owners.stream()
                                        .map(owner -> {
                                            MenuItem ownerItem = new MenuItem(owner.getFullName());
                                            ownerItem.setOnAction(ignoredEvent -> {
                                                browserModel.getSelectedGroup().set(group);
                                                browserModel.getSelectedOwner().set(owner);
                                            });
                                            return ownerItem;
                                        })
                                        .toList()
                        );
                        return menu;
                    } else {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList()
        );

        PredicateTextField<RepositoryEntity> predicateTextField = new PredicateTextField<>(entity ->
                entity.getLabel().get()
        );
        predicateTextField.setPromptText(resources.getString("Browser.ServerBrowser.filterNames"));
        predicateTextField.setIgnoreCase(true);
        HBox.setHgrow(predicateTextField, Priority.ALWAYS);

        hierarchy.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        hierarchy.setRoot(new HierarchyItem(
                client.getServer(),
                browserModel.getSelectedOwner(),
                browserModel.getSelectedGroup(),
                predicateTextField.predicateProperty()
        ));
        hierarchy.setCellFactory(n -> new HierarchyCellFactory(client));

        filterContainer.getChildren().add(0, predicateTextField);

        attributeColumn.setCellValueFactory(cellData -> {
            var selectedItems = hierarchy.getSelectionModel().getSelectedItems();
            if (cellData != null && selectedItems.size() == 1 && selectedItems.get(0).getValue() instanceof ServerEntity serverEntity) {
                return new ReadOnlyObjectWrapper<>(serverEntity.getAttributeName(cellData.getValue()));
            } else {
                return new ReadOnlyObjectWrapper<>("");
            }
        });

        valueColumn.setCellValueFactory(cellData -> {
            var selectedItems = hierarchy.getSelectionModel().getSelectedItems();
            if (cellData != null && selectedItems.size() == 1 && selectedItems.get(0).getValue() instanceof ServerEntity serverEntity) {
                return new ReadOnlyObjectWrapper<>(serverEntity.getAttributeValue(cellData.getValue()));
            } else {
                return new ReadOnlyObjectWrapper<>("");
            }
        });
        valueColumn.setCellFactory(n -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item);
                    setTooltip(new Tooltip(item));
                }
            }
        });

        initOwner(QuPathGUI.getInstance().getStage());
        show();
    }

    private void setUpListeners() {
        username.textProperty().bind(Bindings.when(browserModel.getAuthenticated())
                .then(browserModel.getUsername())
                .otherwise("public")
        );

        numberOpenImages.textProperty().bind(Bindings.size(browserModel.getOpenedImagesURIs()).asString());

        browserModel.getSelectedPixelAPI().addListener(change -> {
            if (browserModel.getSelectedPixelAPI().get() != null && browserModel.getSelectedPixelAPI().get().canAccessRawPixels()) {
                rawPixelAccess.setText(resources.getString("Browser.ServerBrowser.accessRawPixels"));
                rawPixelAccess.setGraphic(UiUtilities.createStateNode(true));
            } else {
                rawPixelAccess.setText(resources.getString("Browser.ServerBrowser.noAccessRawPixels"));
                rawPixelAccess.setGraphic(UiUtilities.createStateNode(false));
            }

            pixelAPI.getSelectionModel().select(browserModel.getSelectedPixelAPI().get());
        });

        pixelAPI.valueProperty().addListener((p, o, n) -> {
            if (pixelAPI.getValue() != null) {
                client.setSelectedPixelAPI(pixelAPI.getValue());
            }
        });

        loadingObjects.visibleProperty().bind(Bindings.notEqual(browserModel.getNumberOfEntitiesLoading(), 0));
        loadingObjects.managedProperty().bind(loadingObjects.visibleProperty());

        loadingOrphaned.textProperty().bind(Bindings.concat(
                resources.getString("Browser.ServerBrowser.loadingOrphanedImages"),
                " (",
                browserModel.getNumberOfOrphanedImagesLoaded(),
                "/",
                browserModel.getNumberOfOrphanedImages(),
                ")"
        ));
        loadingOrphaned.visibleProperty().bind(browserModel.areOrphanedImagesLoading());
        loadingOrphaned.managedProperty().bind(loadingOrphaned.visibleProperty());

        loadingThumbnail.visibleProperty().bind(Bindings.notEqual(browserModel.getNumberOfThumbnailsLoading(), 0));
        loadingThumbnail.managedProperty().bind(loadingThumbnail.visibleProperty());

        groupOwner.textProperty().bind(Bindings.createStringBinding(
                () -> String.format("%s     %s", browserModel.getSelectedGroup().get().getName(), browserModel.getSelectedOwner().get().getFullName()),
                browserModel.getSelectedGroup(), browserModel.getSelectedOwner()
        ));

        hierarchy.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> {
            updateCanvas();
            updateDescription();
            updateImportButton();
        });

        BooleanBinding isSelectedItemOrphanedFolderBinding = Bindings.createBooleanBinding(() ->
                        hierarchy.getSelectionModel().getSelectedItem() != null && hierarchy.getSelectionModel().getSelectedItem().getValue() instanceof OrphanedFolder,
                hierarchy.getSelectionModel().selectedItemProperty()
        );
        moreInfo.disableProperty().bind(isSelectedItemOrphanedFolderBinding);
        openBrowser.disableProperty().bind(isSelectedItemOrphanedFolderBinding);
        copyToClipboard.disableProperty().bind(isSelectedItemOrphanedFolderBinding);

        attributeColumn.prefWidthProperty().bind(description.widthProperty().multiply(DESCRIPTION_ATTRIBUTE_PROPORTION));
        valueColumn.prefWidthProperty().bind(description.widthProperty().multiply(1 - DESCRIPTION_ATTRIBUTE_PROPORTION));

        description.placeholderProperty().bind(Bindings.when(Bindings.isEmpty(hierarchy.getSelectionModel().getSelectedItems()))
                .then(new Label(resources.getString("Browser.ServerBrowser.noElementSelected")))
                .otherwise(new Label(resources.getString("Browser.ServerBrowser.multipleElementsSelected")))
        );

        canvas.managedProperty().bind(Bindings.createBooleanBinding(() ->
                        hierarchy.getSelectionModel().getSelectedItems().size() == 1 &&
                                hierarchy.getSelectionModel().getSelectedItems().get(0).getValue() instanceof Image,
                hierarchy.getSelectionModel().getSelectedItems()));

        browserModel.getSelectedPixelAPI().addListener(change -> updateImportButton());
    }

    private static void collapseTreeView(TreeItem<RepositoryEntity> item){
        if (item != null) {
            for (var child : item.getChildren()) {
                child.setExpanded(false);
            }
        }
    }

    private void updateCanvas() {
        canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());

        var selectedItems = hierarchy.getSelectionModel().getSelectedItems();
        if (selectedItems.size() == 1 && selectedItems.get(0) != null && selectedItems.get(0).getValue() instanceof Image image) {
            client.getApisHandler().getThumbnail(image.getId()).thenAccept(thumbnail -> Platform.runLater(() ->
                    thumbnail.ifPresent(bufferedImage -> UiUtilities.paintBufferedImageOnCanvas(bufferedImage, canvas))
            ));
        }
    }

    private void updateDescription() {
        description.getItems().clear();

        var selectedItems = hierarchy.getSelectionModel().getSelectedItems();
        if (selectedItems.size() == 1 && selectedItems.get(0) != null && selectedItems.get(0).getValue() instanceof ServerEntity serverEntity) {
            description.getItems().setAll(
                    IntStream.rangeClosed(0, serverEntity.getNumberOfAttributes()).boxed().collect(Collectors.toList())
            );
        } else {
            description.getItems().clear();
        }
    }

    private void updateImportButton() {
        var importableEntities = hierarchy.getSelectionModel().getSelectedItems().stream()
                .map(item -> item == null ? null : item.getValue())
                .filter(Objects::nonNull)
                .filter(repositoryEntity -> {
                    if (repositoryEntity instanceof Image image) {
                        return image.isSupported().get();
                    } else {
                        return repositoryEntity instanceof ServerEntity;
                    }
                })
                .toList();

        importImage.setDisable(importableEntities.isEmpty());

        if (importableEntities.isEmpty()) {
            importImage.setText(resources.getString("Browser.ServerBrowser.cantImportSelectedToQuPath"));
        } else if (importableEntities.size() == 1) {
            importImage.setText(MessageFormat.format(
                    resources.getString("Browser.ServerBrowser.importToQuPath"),
                    importableEntities.get(0).getLabel().get()
            ));
        } else {
            importImage.setText(resources.getString("Browser.ServerBrowser.importSelectedToQuPath"));
        }
    }
}
